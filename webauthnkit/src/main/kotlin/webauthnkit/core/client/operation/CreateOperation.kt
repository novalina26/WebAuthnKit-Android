package webauthnkit.core.client.operation

import java.util.*

import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

import webauthnkit.core.*
import webauthnkit.core.CollectedClientData
import webauthnkit.core.PublicKeyCredentialCreationOptions
import webauthnkit.core.authenticator.AttestationObject
import webauthnkit.core.authenticator.MakeCredentialSession
import webauthnkit.core.authenticator.MakeCredentialSessionListener
import webauthnkit.core.util.WKLogger
import webauthnkit.core.util.ByteArrayUtil

@ExperimentalCoroutinesApi
@ExperimentalUnsignedTypes
class CreateOperation(
    private val options: PublicKeyCredentialCreationOptions,
    private val rpId:           String,
    private val session:        MakeCredentialSession,
    private val clientData:     CollectedClientData,
    private val clientDataJSON: String,
    private val clientDataHash: UByteArray,
    private val lifetimeTimer:  Long
) {

    companion object {
        val TAG = this::class.simpleName
    }

    private var stopped: Boolean = false

    private val sessionListener = object : MakeCredentialSessionListener {

        override fun onAvailable(session: MakeCredentialSession) {
            WKLogger.d(TAG, "onAvailable")

            if (stopped) {
                WKLogger.d(TAG, "already stopped")
                return
            }

            if (options.authenticatorSelection != null) {

                val selection = options.authenticatorSelection!!

                if (selection.authenticatorAttachment != null) {
                    if (selection.authenticatorAttachment != session.attachment) {
                        WKLogger.d(TAG, "attachment doesn't match to RP's request")
                        stop(ErrorReason.Unsupported)
                        return
                    }
                }

                if (selection.requireResidentKey && !session.canStoreResidentKey()) {
                    WKLogger.d(TAG, "This authenticator can't store resident-key")
                    stop(ErrorReason.Unsupported)
                    return
                }

                if (selection.userVerification == UserVerificationRequirement.Required
                    && !session.canPerformUserVerification()) {
                    WKLogger.d(TAG, "This authenticator can't perform user verification")
                    stop(ErrorReason.Unsupported)
                    return
                }
            }

            val userVerification = judgeUserVerificationExecution(session)

            val userPresence = !userVerification

            val excludeCredentialDescriptorList =
                    options.excludeCredentials.filter {
                       it.transports.contains(session.transport)
                    }

            val requireResidentKey = options.authenticatorSelection?.requireResidentKey ?: false

            val rpEntity = PublicKeyCredentialRpEntity(
                id = rpId,
                name = options.rp.name,
                icon = options.rp.icon
            )

            session.makeCredential(
                hash                            = clientDataHash,
                rpEntity                        = rpEntity,
                userEntity                      = options.user,
                requireResidentKey              = requireResidentKey,
                requireUserPresence             = userPresence,
                requireUserVerification         = userVerification,
                credTypesAndPubKeyAlgs          = options.pubKeyCredParams,
                excludeCredentialDescriptorList = excludeCredentialDescriptorList
            )
        }

        override fun onCredentialCreated(session: MakeCredentialSession, attestationObject: AttestationObject) {
            WKLogger.d(TAG, "onCredentialCreated")

            val attestedCred = attestationObject.authData.attestedCredentialData
            if (attestedCred == null) {
                WKLogger.w(TAG, "attested credential data not found")
                dispatchError(ErrorReason.Unknown)
                return
            }

            val credId = attestedCred.credentialId

            val resultedAttestationObject: UByteArray?

            if (options.attestation == AttestationConveyancePreference.None
                && attestationObject.isSelfAttestation()) {
                // if it's self attestation,
                // embed 0x00 for aaguid, and empty CBOR map for AttStmt

                val bytes = attestationObject.toNone().toBytes()
                if (bytes == null) {
                    WKLogger.w(TAG, "failed to build attestation object")
                    dispatchError(ErrorReason.Unknown)
                    return
                }

                resultedAttestationObject = bytes


                // replace AAGUID to null
                val guidPos = 37 // ( rpIdHash(32), flag(1), signCount(4) )
                for (idx in (guidPos..(guidPos+15))) {
                    resultedAttestationObject[idx] = 0x00.toUByte()
                }

            } else {
                // if it's other attestation
                // encoded to byte array as it is
                val bytes = attestationObject.toBytes()
                if (bytes == null) {
                    WKLogger.w(TAG, "failed to build attestation object")
                    dispatchError(ErrorReason.Unknown)
                    return
                }
                resultedAttestationObject = bytes
            }

            val response = AuthenticatorAttestationResponse(
                clientDataJSON    = clientDataJSON,
                attestationObject = resultedAttestationObject
            )

            val cred = PublicKeyCredential(
                rawId    = credId,
                id       = ByteArrayUtil.encodeBase64URL(credId),
                response = response
            )

            completed()

            // XXX should be called on UI thread?
            continuation?.resume(cred)
            continuation = null

        }

        override fun onOperationStopped(session: MakeCredentialSession, reason: ErrorReason) {
            WKLogger.d(TAG, "onOperationStopped")
            stop(reason)
        }

        override fun onUnavailable(session: MakeCredentialSession) {
            WKLogger.d(TAG, "onUnavailable")
            stop(ErrorReason.NotAllowed)
        }

    }

    private var continuation: Continuation<MakeCredentialResponse>? = null

    suspend fun start(): MakeCredentialResponse = suspendCoroutine { cont ->

        WKLogger.d(TAG, "start")

        GlobalScope.launch {

            if (stopped) {
                WKLogger.d(TAG, "already stopped")
                cont.resumeWithException(BadOperationException())
                return@launch
            }

            if (continuation != null) {
                WKLogger.d(TAG, "continuation already exists")
                cont.resumeWithException(BadOperationException())
                return@launch
            }

            continuation = cont

            startTimer()

            session.listener = sessionListener
            session.start()
        }
    }

    fun cancel() {
        WKLogger.d(TAG, "cancel")
    }

    private fun stop(reason: ErrorReason) {
        WKLogger.d(TAG, "stop")
        stopInternal(reason)
        dispatchError(reason)
    }

    private fun completed() {
        WKLogger.d(TAG, "completed")
        stopTimer()
    }

    private fun stopInternal(reason: ErrorReason) {
        WKLogger.d(TAG, "stopInternal")
        if (continuation == null) {
            WKLogger.d(TAG, "not started")
           // not started
            return
        }
        if (stopped) {
            WKLogger.d(TAG, "already stopped")
            return
        }
        stopTimer()
        session.cancel(reason)
        // listener!.onFinish()
    }

    private fun dispatchError(reason: ErrorReason) {
        WKLogger.d(TAG, "dispatchError")
        GlobalScope.launch(Dispatchers.Unconfined) {
            continuation?.resumeWithException(reason.rawValue)
        }
    }

    private var timer: Timer? = null

    private fun startTimer() {
        WKLogger.d(TAG, "startTimer")
        stopTimer()
        timer = Timer()
        timer!!.schedule(object: TimerTask(){
            override fun run() {
                timer = null
                onTimeout()
            }
        }, lifetimeTimer*1000)
    }

    private fun stopTimer() {
        WKLogger.d(TAG, "stopTimer")
        timer?.cancel()
        timer = null
    }

    private fun onTimeout() {
        WKLogger.d(TAG, "onTimeout")
        stop(ErrorReason.Timeout)
    }

    private fun judgeUserVerificationExecution(session: MakeCredentialSession): Boolean {
        WKLogger.d(TAG, "judgeUserVerificationExecution")

        val userVerificationRequest =
            options.authenticatorSelection?.userVerification
                ?: UserVerificationRequirement.Discouraged

        return when (userVerificationRequest) {
            UserVerificationRequirement.Required    -> true
            UserVerificationRequirement.Discouraged -> false
            UserVerificationRequirement.Preferred   -> session.canPerformUserVerification()
        }
    }
}
