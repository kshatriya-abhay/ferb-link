package dev.abhay.ferblink

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The keys in Ferb Link's settings never go to a backup or to a new phone. */
class BackupRulesTest {
    private fun xml(path: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File(path)).documentElement

    @Test
    fun `the manifest names the rules, and they exclude every domain from backup and transfer`() {
        val application = xml("src/main/AndroidManifest.xml").getElementsByTagName("application").item(0) as org.w3c.dom.Element
        val android = "http://schemas.android.com/apk/res/android"
        assertThat(application.getAttributeNS(android, "allowBackup")).isEqualTo("false")
        assertThat(application.getAttributeNS(android, "dataExtractionRules")).isEqualTo("@xml/data_extraction_rules")
        val rules = xml("src/main/res/xml/data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val node = rules.getElementsByTagName(section).item(0) as org.w3c.dom.Element
            val excluded = (0 until node.getElementsByTagName("exclude").length).map { node.getElementsByTagName("exclude").item(it) as org.w3c.dom.Element }
                .filter { it.getAttribute("path") == "." }.map { it.getAttribute("domain") }
            assertThat(excluded).containsAtLeast("root", "file", "database", "sharedpref", "external")
            assertThat(node.getElementsByTagName("include").length).isEqualTo(0)
        }
    }
}
