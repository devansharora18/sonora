package dev.sonora.backend

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioQualityTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `formats sample rate with clean integers and single decimal place`() {
        assertEquals("44.1 kHz", AudioQuality.formatSampleRate(44100))
        assertEquals("48 kHz", AudioQuality.formatSampleRate(48000))
        assertEquals("88.2 kHz", AudioQuality.formatSampleRate(88200))
        assertEquals("96 kHz", AudioQuality.formatSampleRate(96000))
        assertEquals("192 kHz", AudioQuality.formatSampleRate(192000))
    }

    @Test
    fun `reads flac streaminfo header bit depth and sample rate`() {
        val flacFile = File(folder.root, "test.flac")
        // Minimal 26-byte header: "fLaC" (4 bytes) + STREAMINFO metadata block header (4 bytes) + 18 bytes info
        // Total 26 bytes minimum.
        // Byte 18-20: sample rate (20 bits), Byte 20-21: channels (3 bits) + bits-per-sample (5 bits)
        // For 96000 Hz: 96000 = 0x017700 -> b18=0x01, b19=0x77, b20 upper 4 bits=0x0
        // For 24-bit: (24 - 1) = 23 = 0x17. 5 bits: 10111. b20 lowest bit = 1, b21 upper 4 bits = 0111 = 0x70.
        val header = ByteArray(26)
        header[0] = 'f'.code.toByte()
        header[1] = 'L'.code.toByte()
        header[2] = 'a'.code.toByte()
        header[3] = 'C'.code.toByte()
        header[18] = 0x17.toByte()
        header[19] = 0x70.toByte()
        header[20] = 0x01.toByte() // sampleRate low nibble = 0, bitDepth bit 4 = 1
        header[21] = 0x70.toByte() // bitDepth lower 4 bits = 7 (0b0111) -> 0b10111 = 23 -> 23 + 1 = 24 bits

        flacFile.writeBytes(header)

        val (bitDepth, sampleRate) = AudioQuality.readFlacHeader(flacFile)
        assertEquals(24, bitDepth)
        assertEquals(96000, sampleRate)

        val quality = AudioQuality.from(flacFile)
        assertEquals("FLAC  ·  24-bit  ·  96 kHz", quality)
    }

    @Test
    fun `reads wav fmt chunk bit depth and sample rate`() {
        val wavFile = File(folder.root, "test.wav")
        val header = ByteArray(44)
        // "RIFF"
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        // "WAVE"
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        // "fmt "
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        // sample rate: 44100 = 0x0000AC44 (little endian: 0x44, 0xAC, 0x00, 0x00)
        header[24] = 0x44.toByte()
        header[25] = 0xAC.toByte()
        header[26] = 0x00.toByte()
        header[27] = 0x00.toByte()
        // bits per sample: 16 = 0x0010 (little endian: 0x10, 0x00)
        header[34] = 0x10.toByte()
        header[35] = 0x00.toByte()

        wavFile.writeBytes(header)

        val (bitDepth, sampleRate) = AudioQuality.readWavHeader(wavFile)
        assertEquals(16, bitDepth)
        assertEquals(44100, sampleRate)

        val quality = AudioQuality.from(wavFile)
        assertEquals("WAV  ·  16-bit  ·  44.1 kHz", quality)
    }
}
