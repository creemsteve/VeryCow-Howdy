package chat.vch;

import javax.sound.sampled.*;
import java.io.IOException;
import java.nio.*;

/** Native mono PCM devices. The recording line is stopped outside a granted PTT lease. */
interface VoiceAudio extends AutoCloseable {
    void open(String microphone, String speakers) throws Exception;
    void recording(boolean active);
    int read(byte[] pcm);
    void play(byte[] pcm);
    void silence();
    void close();

    static VoiceAudio system() { return new Devices(); }

    static byte[] toFloat(byte[] pcm, int length, int gain) {
        ByteBuffer out = ByteBuffer.allocate(length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < length; i += 2) {
            short sample = (short) ((pcm[i] & 255) | (pcm[i + 1] << 8));
            out.putFloat(Math.max(-1, Math.min(1, sample / 32768f * gain / 100f)));
        }
        return out.array();
    }

    static byte[] toPcm(byte[] floats, int gain) {
        FloatBuffer input = ByteBuffer.wrap(floats).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        ByteBuffer out = ByteBuffer.allocate(input.remaining() * 2).order(ByteOrder.LITTLE_ENDIAN);
        while (input.hasRemaining()) {
            float value = input.get() * gain / 100f;
            out.putShort((short) Math.round(Math.max(-1, Math.min(1, Float.isFinite(value) ? value : 0)) * 32767));
        }
        return out.array();
    }

    final class Devices implements VoiceAudio {
        private static final AudioFormat FORMAT = new AudioFormat(16000, 16, 1, true, false);
        private volatile TargetDataLine microphone;
        private volatile SourceDataLine speakers;
        public synchronized void open(String input, String output) throws Exception {
            try {
                microphone = (TargetDataLine) line(TargetDataLine.class, input);
                microphone.open(FORMAT, 32000);
                speakers = (SourceDataLine) line(SourceDataLine.class, output);
                speakers.open(FORMAT, 32000); speakers.start();
            } catch (Exception e) { close(); throw new IOException("Cannot open audio devices: " + e.getMessage(), e); }
        }
        private Line line(Class<? extends DataLine> type, String name) throws Exception {
            DataLine.Info info = new DataLine.Info(type, FORMAT);
            if (!name.equals("Default") && !name.isBlank()) {
                for (Mixer.Info candidate : AudioSystem.getMixerInfo()) if (candidate.getName().equals(name))
                    return AudioSystem.getMixer(candidate).getLine(info);
                throw new IOException("Audio device is unavailable: " + name);
            }
            return AudioSystem.getLine(info);
        }
        public void recording(boolean active) {
            TargetDataLine line = microphone;
            if (line == null || !line.isOpen()) return;
            if (active) { line.flush(); line.start(); } else { line.stop(); line.flush(); }
        }
        public int read(byte[] pcm) { TargetDataLine line = microphone; return line == null ? 0 : line.read(pcm, 0, pcm.length); }
        public void play(byte[] pcm) {
            SourceDataLine line = speakers;
            for (int offset = 0; line != null && line.isOpen() && offset < pcm.length;) {
                int count = line.write(pcm, offset, pcm.length - offset); if (count <= 0) break; offset += count;
            }
        }
        public void silence() { SourceDataLine line = speakers; if (line != null && line.isOpen()) line.flush(); }
        public synchronized void close() {
            TargetDataLine input = microphone; SourceDataLine output = speakers; microphone = null; speakers = null;
            if (input != null) { input.stop(); input.flush(); input.close(); }
            if (output != null) { output.stop(); output.flush(); output.close(); }
        }
    }
}
