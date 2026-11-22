package tw.bard.carradar.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AssetsParsingTest {

    @Test
    fun `real zones json parses cleanly without crashing on null b`() {
        val f = File("src/main/assets/zones.json")
        val file = if (f.exists()) f else File("app/src/main/assets/zones.json")
        assertTrue("zones.json should exist", file.exists())

        val zones = CameraRepository.parseZonesJson(file.readText(Charsets.UTF_8))
        assertTrue("Should parse valid zones", zones.isNotEmpty())
    }

    @Test
    fun `real cameras json parses cleanly`() {
        val f = File("src/main/assets/cameras.json")
        val file = if (f.exists()) f else File("app/src/main/assets/cameras.json")
        assertTrue("cameras.json should exist", file.exists())

        val cams = CameraRepository.parseCamerasJsonStatic(file.readText(Charsets.UTF_8))
        assertTrue("Should parse > 1500 cameras", cams.size > 1500)
    }
}
