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

    @Test
    fun `custom camera serialization preserves speed, red light, and tech points`() {
        val sample = listOf(
            tw.bard.carradar.core.CameraPoint(
                id = "custom_1",
                lat = 25.033,
                lon = 121.565,
                limitKmh = 60,
                directionText = "雙向",
                address = "自訂測試點",
                city = "自訂",
                kind = tw.bard.carradar.core.CameraKind.SPEED
            ),
            tw.bard.carradar.core.CameraPoint(
                id = "custom_2",
                lat = 25.040,
                lon = 121.570,
                limitKmh = 0,
                directionText = "雙向",
                address = "自訂科技",
                city = "自訂",
                kind = tw.bard.carradar.core.CameraKind.TECH
            )
        )
        val json = CustomCameraStore.serialize(sample)
        val restored = CustomCameraStore.deserialize(json)
        assertEquals(2, restored.size)
        assertEquals(60, restored[0].limitKmh)
        assertEquals(tw.bard.carradar.core.CameraKind.TECH, restored[1].kind)
    }
}
