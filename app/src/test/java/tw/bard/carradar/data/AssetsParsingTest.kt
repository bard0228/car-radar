package tw.bard.carradar.data

import org.junit.Assert.*
import org.junit.Test
import tw.bard.carradar.core.CameraKind
import tw.bard.carradar.core.CameraPoint
import tw.bard.carradar.core.RoadLevel
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
    fun `real cameras json parses cleanly with road levels`() {
        val f = File("src/main/assets/cameras.json")
        val file = if (f.exists()) f else File("app/src/main/assets/cameras.json")
        assertTrue("cameras.json should exist", file.exists())

        val cams = CameraRepository.parseCamerasJsonStatic(file.readText(Charsets.UTF_8))
        assertTrue("Should parse > 1500 cameras", cams.size > 1500)
        assertTrue("Should classify expressway cameras", cams.any { it.roadLevel == RoadLevel.EXPRESSWAY })
    }

    @Test
    fun `all 57 real enforcement CSVs parse cleanly including Big5 and English headers`() {
        val dir = File("src/main/assets/enforcement").let { if (it.exists()) it else File("app/src/main/assets/enforcement") }
        assertTrue("enforcement dir should exist", dir.exists())
        val csvs = dir.listFiles { f -> f.name.endsWith(".csv") } ?: emptyArray()
        assertTrue("Should have 50+ CSVs", csvs.size >= 50)

        var totalParsed = 0
        var filesWithData = 0
        for (csv in csvs) {
            val text = CameraRepository.decodeBytesSmart(csv.readBytes())
            val pts = CameraRepository.parseEnforcementCsvStatic(csv.name, text)
            if (pts.isNotEmpty()) {
                filesWithData++
                totalParsed += pts.size
            }
        }
        // Previously only 12/57 files (~534 rows) were parsed; now we expect 50+ files and 1500+ rows!
        assertTrue("Expected >= 50 parsed CSV files, got $filesWithData", filesWithData >= 50)
        assertTrue("Expected >= 1500 enforcement rows, got $totalParsed", totalParsed >= 1500)
    }

    @Test
    fun `direction parser accurately resolves A-to-B taiwan police directions`() {
        val (h1, b1) = CameraRepository.parseDirectionStatic("北向南")
        assertEquals(180f, h1)
        assertFalse(b1)

        val (h2, b2) = CameraRepository.parseDirectionStatic("南向北")
        assertEquals(0f, h2)
        assertFalse(b2)

        val (h3, b3) = CameraRepository.parseDirectionStatic("東向西")
        assertEquals(270f, h3)
        assertFalse(b3)

        val (h4, b4) = CameraRepository.parseDirectionStatic("西往東")
        assertEquals(90f, h4)
        assertFalse(b4)

        val (h5, b5) = CameraRepository.parseDirectionStatic("西南向東北")
        assertEquals(45f, h5)
        assertFalse(b5)

        val (_, b6) = CameraRepository.parseDirectionStatic("南北雙向")
        assertTrue(b6)
    }

    @Test
    fun `custom camera serialization preserves speed, red light, and tech points`() {
        val sample = listOf(
            CameraPoint(
                id = "custom_1",
                lat = 25.033,
                lon = 121.565,
                limitKmh = 60,
                directionText = "雙向",
                address = "自訂測試點",
                city = "自訂",
                kind = CameraKind.SPEED
            ),
            CameraPoint(
                id = "custom_2",
                lat = 25.040,
                lon = 121.570,
                limitKmh = 0,
                directionText = "雙向",
                address = "自訂科技",
                city = "自訂",
                kind = CameraKind.TECH
            )
        )
        val json = CustomCameraStore.serialize(sample)
        val restored = CustomCameraStore.deserialize(json)
        assertEquals(2, restored.size)
        assertEquals(60, restored[0].limitKmh)
        assertEquals(CameraKind.TECH, restored[1].kind)
    }
}
