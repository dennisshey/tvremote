package com.sidephone.atvremote.companion

/**
 * The two HAP procedures carried over Companion:
 *  - [CompanionPairSetup]  runs once, while the Apple TV shows a 4-digit PIN.
 *  - [CompanionPairVerify] runs on every later connection to re-establish the
 *    encrypted session from stored credentials.
 */
private const val PAIRING_DATA_KEY = "_pd"

private fun pairingData(response: OpackDict): Map<Int, ByteArray> {
    val raw = response[PAIRING_DATA_KEY] as? ByteArray
        ?: throw CompanionProtocolException("No pairing data in response")
    val tlv = Tlv8.read(raw)
    tlv[Tlv8.ERROR]?.let { error ->
        val code = if (error.isNotEmpty()) error[0].toInt() and 0xFF else -1
        throw CompanionProtocolException("Pairing rejected by device (error $code)")
    }
    return tlv
}

class CompanionPairSetup(
    private val protocol: CompanionProtocol,
    private val srp: SrpAuthHandler,
) {
    private lateinit var atvSalt: ByteArray
    private lateinit var atvPubKey: ByteArray

    /** M1/M2: request that the Apple TV display a PIN and hand back salt + B. */
    suspend fun start() {
        srp.setupInitialize()
        val response = protocol.exchangeAuth(
            FrameType.PS_Start,
            mapOf(
                PAIRING_DATA_KEY to Tlv8.write(
                    linkedMapOf(
                        Tlv8.METHOD to byteArrayOf(0x00),
                        Tlv8.SEQ_NO to byteArrayOf(0x01),
                    )
                ),
                "_pwTy" to 1,
            ),
        )
        val data = pairingData(response)
        atvSalt = data.getValue(Tlv8.SALT)
        atvPubKey = data.getValue(Tlv8.PUBLIC_KEY)
    }

    /** M3–M6: prove knowledge of the PIN and exchange long-term keys. */
    suspend fun finish(pin: String, displayName: String?): HapCredentials {
        val (publicKey, proof) = srp.step2(atvPubKey, atvSalt, pin)

        protocol.exchangeAuth(
            FrameType.PS_Next,
            mapOf(
                PAIRING_DATA_KEY to Tlv8.write(
                    linkedMapOf(
                        Tlv8.SEQ_NO to byteArrayOf(0x03),
                        Tlv8.PUBLIC_KEY to publicKey,
                        Tlv8.PROOF to proof,
                    )
                ),
                "_pwTy" to 1,
            ),
        )

        val encrypted = srp.step3(displayName)
        val response = protocol.exchangeAuth(
            FrameType.PS_Next,
            mapOf(
                PAIRING_DATA_KEY to Tlv8.write(
                    linkedMapOf(
                        Tlv8.SEQ_NO to byteArrayOf(0x05),
                        Tlv8.ENCRYPTED_DATA to encrypted,
                    )
                ),
                "_pwTy" to 1,
            ),
        )

        val data = pairingData(response)
        return srp.step4(data.getValue(Tlv8.ENCRYPTED_DATA))
    }
}

class CompanionPairVerify(
    private val protocol: CompanionProtocol,
    private val srp: SrpAuthHandler,
    private val credentials: HapCredentials,
) {
    /** Verify credentials and switch the connection to its encrypted session. */
    suspend fun verifyAndEnableEncryption() {
        val publicKey = srp.verifyInitialize()

        val response = protocol.exchangeAuth(
            FrameType.PV_Start,
            mapOf(
                PAIRING_DATA_KEY to Tlv8.write(
                    linkedMapOf(
                        Tlv8.SEQ_NO to byteArrayOf(0x01),
                        Tlv8.PUBLIC_KEY to publicKey,
                    )
                ),
                "_auTy" to 4,
            ),
        )

        val data = pairingData(response)
        val serverPubKey = data.getValue(Tlv8.PUBLIC_KEY)
        val encrypted = data.getValue(Tlv8.ENCRYPTED_DATA)

        val proof = srp.verify1(credentials, serverPubKey, encrypted)

        protocol.exchangeAuth(
            FrameType.PV_Next,
            mapOf(
                PAIRING_DATA_KEY to Tlv8.write(
                    linkedMapOf(
                        Tlv8.SEQ_NO to byteArrayOf(0x03),
                        Tlv8.ENCRYPTED_DATA to proof,
                    )
                ),
            ),
        )

        val (outputKey, inputKey) = srp.verify2("", "ClientEncrypt-main", "ServerEncrypt-main")
        protocol.enableEncryption(outputKey, inputKey)
    }
}
