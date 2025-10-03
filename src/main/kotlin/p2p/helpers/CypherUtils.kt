package p2p.helpers

import p2p.domain.PeerSignature
import p2p.domain.wtfs.PeerPrivateKey
import p2p.domain.wtfs.PeerPublicKey
import p2p.utils.toByteArray
import java.security.*
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.PSSParameterSpec
import java.security.spec.X509EncodedKeySpec

class CypherUtils {
    companion object {
        private const val RSA_ALGORITHM = "RSA"
        private const val SIGNATURE_ALGORITHM = "RSASSA-PSS"
        private const val HASH_ALGORITHM = "SHA-256"
        
        /**
         * Signs data using RSA-PSS with SHA-256
         * @param data The data to sign
         * @param privateKey The RSA private key (4096-bit)
         * @return The signature bytes (512 bytes)
         */
        fun signData(data: ByteArray, privateKey: PeerPrivateKey): PeerSignature {
            try {
                val keyFactory = KeyFactory.getInstance(RSA_ALGORITHM)
                val privateKeySpec = PKCS8EncodedKeySpec(privateKey.toByteArray())
                val rsaPrivateKey = keyFactory.generatePrivate(privateKeySpec)
                
                val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
                
                // Configure RSA-PSS parameters
                val pssSpec = PSSParameterSpec(
                    HASH_ALGORITHM,
                    "MGF1",
                    MGF1ParameterSpec.SHA256,
                    32, // Salt length (SHA-256 output size)
                    1   // Trailer field
                )
                signature.setParameter(pssSpec)
                
                signature.initSign(rsaPrivateKey)
                signature.update(data)
                
                return signature.sign()
            } catch (e: Exception) {
                throw SecurityException("Failed to sign data: ${e.message}", e)
            }
        }

        /**
         * Validates an RSA-PSS signature with SHA-256
         * @param data The original data that was signed
         * @param signature The signature to validate
         * @param publicKey The RSA public key (4096-bit)
         * @return True if signature is valid, false otherwise
         */
        fun validateSignature(data: ByteArray, signature: PeerSignature, publicKey: PeerPublicKey): Boolean {
            return try {
                val keyFactory = KeyFactory.getInstance(RSA_ALGORITHM)
                val publicKeySpec = X509EncodedKeySpec(publicKey.toByteArray())
                val rsaPublicKey = keyFactory.generatePublic(publicKeySpec)
                
                val sig = Signature.getInstance(SIGNATURE_ALGORITHM)
                
                // Configure RSA-PSS parameters (must match signing parameters)
                val pssSpec = PSSParameterSpec(
                    HASH_ALGORITHM,
                    "MGF1",
                    MGF1ParameterSpec.SHA256,
                    32, // Salt length (SHA-256 output size)
                    1   // Trailer field
                )
                sig.setParameter(pssSpec)
                
                sig.initVerify(rsaPublicKey)
                sig.update(data)
                
                sig.verify(signature)
            } catch (e: Exception) {
                // Log the error but return false for invalid signatures
                false
            }
        }
        
        /**
         * Creates a signed PodMetadata with proper signature
         * @param owner The owner's public key
         * @param number The pod number
         * @param merkleHashRoot The merkle hash root of the pod content
         * @param updatedAt The timestamp when the pod was last updated
         * @param privateKey The owner's private key for signing
         * @return Fully signed PodMetadata
         */
        fun createSignedPodMetadata(
            owner: PeerPublicKey,
            number: Int,
            merkleHashRoot: ByteArray,
            updatedAt: Long,
            privateKey: PeerPrivateKey
        ): p2p.domain.wtfs.PodMetadata {
            // Create the data to sign: owner + number + merkleHashRoot + updatedAt
            val dataToSign = owner.toByteArray() + 
                            number.toByteArray() + 
                            merkleHashRoot + 
                            updatedAt.toByteArray()
            
            val signature = signData(dataToSign, privateKey)
            
            return p2p.domain.wtfs.PodMetadata(owner, number, merkleHashRoot, updatedAt, signature)
        }
        
        /**
         * Validates a PodMetadata signature
         * @param podMetadata The pod metadata to validate
         * @return True if the signature is valid, false otherwise
         */
        fun validatePodMetadata(podMetadata: p2p.domain.wtfs.PodMetadata): Boolean {
            // Reconstruct the same data that should have been signed
            val dataToVerify = podMetadata.owner.toByteArray() + 
                              podMetadata.number.toByteArray() + 
                              podMetadata.merkleHashRoot + 
                              podMetadata.updatedAt.toByteArray()
            
            return validateSignature(dataToVerify, podMetadata.ownerSignature, podMetadata.owner)
        }
    }
}