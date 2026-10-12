package io.github.intramuros.weatherbuddy.data

import kotlin.test.Test
import kotlin.test.assertEquals

class MultipartTest {
    @Test
    fun writesFieldsAndFilesWithTheirTypes() {
        val webp = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val form = Multipart("XYZ")
        form.field("prompt", "Snow, cold. Café")
        form.file("image[]", "image-1", webp)
        form.file("image[]", "image-2", png)

        assertEquals("multipart/form-data; boundary=XYZ", form.contentType)
        val expected = "--XYZ\r\n" +
            "Content-Disposition: form-data; name=\"prompt\"\r\n\r\n" +
            "Snow, cold. Café\r\n" +
            "--XYZ\r\n" +
            "Content-Disposition: form-data; name=\"image[]\"; filename=\"image-1.webp\"\r\n" +
            "Content-Type: image/webp\r\n\r\n" +
            String(webp, Charsets.ISO_8859_1) + "\r\n" +
            "--XYZ\r\n" +
            "Content-Disposition: form-data; name=\"image[]\"; filename=\"image-2.png\"\r\n" +
            "Content-Type: image/png\r\n\r\n" +
            String(png, Charsets.ISO_8859_1) + "\r\n" +
            "--XYZ--\r\n"
        // Text is UTF-8; compare bytes as Latin-1 so the binary parts survive.
        val utf8 = expected.replace("Café", String("Café".toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1))
        assertEquals(utf8, String(form.bytes(), Charsets.ISO_8859_1))
    }
}
