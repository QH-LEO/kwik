package net.luminis.quic;


import net.luminis.tls.TlsState;

import java.nio.ByteBuffer;

// https://tools.ietf.org/html/draft-ietf-quic-transport-16#section-17.2
public abstract class LongHeaderPacket extends QuicPacket {

    private static final int MAX_PACKET_SIZE = 1500;
    
    protected  Version quicVersion;
    protected  byte[] sourceConnectionId;
    protected  byte[] destConnectionId;
    private  int packetNumber;
    protected  byte[] payload;
    protected  ConnectionSecrets connectionSecrets;
    protected TlsState tlsState;
    protected  ByteBuffer packetBuffer;
    private int packetNumberPosition;
    private int encodedPacketNumberSize;
    private int paddingLength;
    private byte[] encryptedPayload;

    public LongHeaderPacket(Version quicVersion, ConnectionSecrets connectionSecrets, TlsState tlsState) {
        this.quicVersion = quicVersion;
        this.connectionSecrets = connectionSecrets;
        this.tlsState = tlsState;
    }

    public LongHeaderPacket(Version quicVersion, byte[] sourceConnectionId, byte[] destConnectionId, int packetNumber, byte[] payload, ConnectionSecrets connectionSecrets) {
        this.quicVersion = quicVersion;
        this.sourceConnectionId = sourceConnectionId;
        this.destConnectionId = destConnectionId;
        this.packetNumber = packetNumber;
        this.payload = payload;
        this.connectionSecrets = connectionSecrets;

        packetBuffer = ByteBuffer.allocate(MAX_PACKET_SIZE);
        generateFrameHeaderInvariant();
        generateAdditionalFields();
        byte[] encodedPacketNumber = encodePacketNumber(packetNumber);
        addLength(encodedPacketNumber.length);
        addPacketNumber(encodedPacketNumber);

        generateEncryptPayload(payload);
        protectPacketNumber();

        packetBuffer.limit(packetBuffer.position());
    }

    protected void generateFrameHeaderInvariant() {
        // type (initial)
        packetBuffer.put((byte) 0xff);
        // Version
        packetBuffer.put(quicVersion.getBytes());
        // DCIL / SCIL
        byte dcil = (byte) ((destConnectionId.length - 3) << 4);
        byte scil = (byte) (sourceConnectionId.length - 3);
        packetBuffer.put((byte) (dcil | scil));
        // Destination connection id
        packetBuffer.put(destConnectionId);
        // Source connection id, 8 bytes
        packetBuffer.put(sourceConnectionId);
    }

    protected abstract void generateAdditionalFields();

    private void addLength(int packetNumberLength) {
        int estimatedPacketLength = packetBuffer.position() + packetNumberLength + payload.length + 16;   // 16 is what encryption adds, note that final length is larger due to adding packet length
        paddingLength = 0;
        // Initial packet should at least be 1200 bytes (https://tools.ietf.org/html/draft-ietf-quic-transport-16#section-14)
        if (estimatedPacketLength < 1200)
            paddingLength = 1200 - estimatedPacketLength;
        int packetLength = payload.length + paddingLength + 16 + packetNumberLength;
        byte[] length = encodeVariableLengthInteger(packetLength);
        packetBuffer.put(length);
    }

    private void addPacketNumber(byte[] encodedPacketNumber) {
        // Remember packet number position in buffer, for protected in later.
        packetNumberPosition = packetBuffer.position();
        encodedPacketNumberSize = encodedPacketNumber.length;
        packetBuffer.put(encodedPacketNumber);
    }

    private void generateEncryptPayload(byte[] payload) {
        // From https://tools.ietf.org/html/draft-ietf-quic-tls-16#section-5.3:
        // "The associated data, A, for the AEAD is the contents of the QUIC
        //   header, starting from the flags octet in either the short or long
        //   header, up to and including the unprotected packet number."
        int additionalDataSize = packetBuffer.position();
        byte[] additionalData = new byte[additionalDataSize];
        packetBuffer.flip();  // Prepare for reading from start
        packetBuffer.get(additionalData);  // Position is now where it was at start of this method.
        packetBuffer.limit(packetBuffer.capacity());  // Ensure we can continue writing

        byte[] paddedPayload = new byte[payload.length + paddingLength];
        System.arraycopy(payload, 0, paddedPayload, 0, payload.length);
        encryptedPayload = encryptPayload(paddedPayload, additionalData, packetNumber, connectionSecrets.clientSecrets);
        packetBuffer.put(encryptedPayload);
    }

    private void protectPacketNumber() {
        byte[] protectedPacketNumber = createProtectedPacketNumber(encryptedPayload, 0, connectionSecrets.clientSecrets);
        int currentPosition = packetBuffer.position();
        packetBuffer.position(packetNumberPosition);
        packetBuffer.put(protectedPacketNumber);
        packetBuffer.position(currentPosition);
    }

    public byte[] getBytes() {
        byte[] packetBytes = new byte[packetBuffer.position()];
        packetBuffer.rewind();
        packetBuffer.get(packetBytes);
        return packetBytes;
    }

    public void parse(ByteBuffer buffer, Logger log) {
        log.debug("Parsing " + this.getClass().getSimpleName());
        checkPacketType(buffer.get());

        try {
            Version quicVersion = Version.parse(buffer.getInt());
        } catch (UnknownVersionException e) {
            // Protocol error: if it gets here, server should match the Quic version we sent
            throw new ProtocolError("Server uses unsupported Quic version");
        }

        byte dcilScil = buffer.get();
        int dstConnIdLength = ((dcilScil & 0xf0) >> 4) + 3;
        int srcConnIdLength = (dcilScil & 0x0f) + 3;

        byte[] destConnId = new byte[dstConnIdLength];
        buffer.get(destConnId);
        log.debug("Destination connection id", destConnId);
        byte[] srcConnId = new byte[srcConnIdLength];
        buffer.get(srcConnId);
        log.debug("Source connection id", srcConnId);

        parseAdditionalFields(buffer);

        int length = parseVariableLengthInteger(buffer);
        log.debug("Length (PN + payload): " + length);

        int protectedPackageNumberLength = 1;
        byte[] protectedPackageNumber = new byte[protectedPackageNumberLength];
        buffer.get(protectedPackageNumber);

        int currentPosition = buffer.position();
        byte[] frameHeader = new byte[buffer.position()];
        buffer.position(0);
        buffer.get(frameHeader);
        buffer.position(currentPosition);

        byte[] payload = new byte[length - protectedPackageNumberLength];
        buffer.get(payload, 0, length - protectedPackageNumberLength);

        int packetNumber = unprotectPacketNumber(payload, protectedPackageNumber, connectionSecrets.serverSecrets);
        log.debug("Packet number: " + packetNumber);

        log.debug("Encrypted payload", payload);

        frameHeader[frameHeader.length - 1] = (byte) packetNumber;   // TODO: assuming packet number is 1 byte
        log.debug("Frame header", frameHeader);

        byte[] frames = decryptPayload(payload, frameHeader, packetNumber, connectionSecrets.serverSecrets);
        log.debug("Decrypted payload", frames);
        parseFrames(frames, log);
    }


    protected void parseFrames(byte[] frames, Logger log) {
        ByteBuffer buffer = ByteBuffer.wrap(frames);

        while (buffer.remaining() > 0) {
            // https://tools.ietf.org/html/draft-ietf-quic-transport-16#section-12.4
            // "Each frame begins with a Frame Type, indicating its type, followed by additional type-dependent fields"
            int frameType = buffer.get();
            switch (frameType) {
                case 0x00:
                    // Padding
                    break;
                case 0x0d:
                    if (quicVersion == Version.IETF_draft_14)
                        new AckFrame().parse(buffer, log);
                    else
                        throw new NotYetImplementedException();
                    break;
                case 0x18:
                    new CryptoFrame(connectionSecrets, tlsState).parse(buffer, log);
                    break;
                case 0x1a:
                    if (quicVersion.atLeast(Version.IETF_draft_15))
                        new AckFrame().parse(buffer, log);
                    else
                        throw new NotYetImplementedException();
                    break;
                case 0x1b:
                    if (quicVersion.atLeast(Version.IETF_draft_15))
                        new AckFrame().parse(buffer, log);
                    else
                        throw new NotYetImplementedException();
                    break;
                default:
                    throw new NotYetImplementedException();
            }
        }
    }

    protected abstract void checkPacketType(byte b);

    protected abstract void parseAdditionalFields(ByteBuffer buffer);
}

