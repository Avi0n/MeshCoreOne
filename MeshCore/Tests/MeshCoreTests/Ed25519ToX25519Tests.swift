import CommonCrypto
import CryptoKit
import Foundation
import Testing
@testable import MeshCore

@Suite("Ed25519ToX25519")
struct Ed25519ToX25519Tests {

    /// Simulate the firmware's key expansion: SHA-512(seed), clamp first 32 bytes.
    /// This is what `ed25519_create_keypair` does in the MeshCore firmware.
    private func expandSeed(_ seed: Data) -> Data {
        var hash = Data(SHA512.hash(data: seed))
        hash[0] &= 248
        hash[31] &= 63
        hash[31] |= 64
        return Data(hash.prefix(32))
    }

    @Test("Public key conversion round-trip with CryptoKit")
    func publicKeyConversionRoundTrip() throws {
        // Generate two Ed25519 keypairs and expand seeds (simulating firmware)
        let aliceSigning = Curve25519.Signing.PrivateKey()
        let bobSigning = Curve25519.Signing.PrivateKey()

        let aliceScalar = expandSeed(aliceSigning.rawRepresentation)
        let bobScalar = expandSeed(bobSigning.rawRepresentation)

        // Convert public keys to X25519
        let aliceX25519Public = try #require(Ed25519ToX25519.convertPublicKey(Data(aliceSigning.publicKey.rawRepresentation)))
        let bobX25519Public = try #require(Ed25519ToX25519.convertPublicKey(Data(bobSigning.publicKey.rawRepresentation)))

        // Compute shared secrets both ways
        let aliceKA = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: aliceScalar)
        let bobKA = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: bobScalar)

        let bobX25519PK = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: bobX25519Public)
        let aliceX25519PK = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: aliceX25519Public)

        let sharedAB = try aliceKA.sharedSecretFromKeyAgreement(with: bobX25519PK)
        let sharedBA = try bobKA.sharedSecretFromKeyAgreement(with: aliceX25519PK)

        let abBytes = sharedAB.withUnsafeBytes { Data($0) }
        let baBytes = sharedBA.withUnsafeBytes { Data($0) }
        #expect(abBytes == baBytes)
    }

    @Test("DM decrypt with converted Ed25519 keys")
    func dmDecryptWithConvertedKeys() throws {
        // Simulate firmware: Ed25519 keys → expanded scalars + converted public keys
        let senderSigning = Curve25519.Signing.PrivateKey()
        let recipientSigning = Curve25519.Signing.PrivateKey()

        let senderScalar = expandSeed(senderSigning.rawRepresentation)
        let recipientScalar = expandSeed(recipientSigning.rawRepresentation)
        let senderX25519Public = try #require(Ed25519ToX25519.convertPublicKey(Data(senderSigning.publicKey.rawRepresentation)))
        let recipientX25519Public = try #require(Ed25519ToX25519.convertPublicKey(Data(recipientSigning.publicKey.rawRepresentation)))

        // Sender computes shared secret (simulating firmware ECDH)
        let senderKA = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: senderScalar)
        let recipientPK = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: recipientX25519Public)
        let sharedSecret = try senderKA.sharedSecretFromKeyAgreement(with: recipientPK)
        let secretData = sharedSecret.withUnsafeBytes { Data($0) }

        // Build encrypted DM payload
        let timestamp: UInt32 = 1703123456
        var plaintext = Data()
        var ts = timestamp.littleEndian
        plaintext.append(Data(bytes: &ts, count: 4))
        plaintext.append(0) // typeAttempt
        plaintext.append(Data("Hello".utf8))

        let paddedLen = ((plaintext.count + 15) / 16) * 16
        while plaintext.count < paddedLen { plaintext.append(0) }

        var ciphertext = Data(count: paddedLen)
        var numBytes: size_t = 0
        let keyBytes = secretData.prefix(16)

        ciphertext.withUnsafeMutableBytes { cPtr in
            plaintext.withUnsafeBytes { pPtr in
                keyBytes.withUnsafeBytes { kPtr in
                    CCCrypt(
                        UInt32(kCCEncrypt),
                        UInt32(kCCAlgorithmAES),
                        UInt32(kCCOptionECBMode),
                        kPtr.baseAddress, 16,
                        nil,
                        pPtr.baseAddress, paddedLen,
                        cPtr.baseAddress, paddedLen,
                        &numBytes
                    )
                }
            }
        }
        ciphertext = Data(ciphertext.prefix(numBytes))

        let symmetricKey = SymmetricKey(data: secretData)
        let mac = Data(HMAC<SHA256>.authenticationCode(for: ciphertext, using: symmetricKey).prefix(2))

        var packet = Data()
        packet.append(recipientX25519Public[0])
        packet.append(senderX25519Public[0])
        packet.append(mac)
        packet.append(ciphertext)

        // Recipient decrypts with their scalar and sender's converted public key
        let result = DirectMessageCrypto.decrypt(
            payload: packet,
            myPrivateKey: recipientScalar,
            senderPublicKey: senderX25519Public
        )

        switch result {
        case .success(let ts, _, let text):
            #expect(ts == timestamp)
            #expect(text == "Hello")
        default:
            Issue.record("Expected success, got: \(result)")
        }
    }

    @Test("Conversion rejects invalid inputs")
    func invalidInputs() {
        #expect(Ed25519ToX25519.convertPublicKey(Data()) == nil)
        #expect(Ed25519ToX25519.convertPublicKey(Data(repeating: 0, count: 16)) == nil)
    }
}
