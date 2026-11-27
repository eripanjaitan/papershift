package com.papershift.platform

import com.google.common.truth.Truth.assertThat
import org.json.JSONException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** TC-BING-01. Robolectric provides a real org.json. */
@RunWith(RobolectricTestRunner::class)
class BingClientParseTest {

    // Shape of the live response checked on 2026-11-26; extra fields must be ignored.
    private val sample = """
        {"images":[
          {"startdate":"20261126","fullstartdate":"202611260800","enddate":"20261127",
           "url":"/th?id=OHR.WildTom_EN-US1069249609_1920x1080.jpg&rf=LaDigue_1920x1080.jpg",
           "urlbase":"/th?id=OHR.WildTom_EN-US1069249609",
           "copyright":"Two wild tom turkeys (© davidsdodd/Getty Images)",
           "copyrightlink":"https://www.bing.com/search?q=Thanksgiving&form=hpcapt",
           "title":"Info","quiz":"/search?q=Bing+homepage+quiz","wp":true,"hsh":"abc","drk":1,"top":1,"bot":1,"hs":[]},
          {"startdate":"20261125","urlbase":"/th?id=OHR.Other_EN-US1","copyright":"Other (© Someone)","copyrightlink":"javascript:void(0)"},
          {"startdate":"","urlbase":"/th?id=OHR.Broken"}
        ],"tooltips":{"loading":"Loading..."}}
    """.trimIndent()

    @Test
    fun `parses required fields and keeps archive order`() {
        val images = BingClient.parseArchive(sample)
        assertThat(images.map { it.startDate }).containsExactly("20261126", "20261125").inOrder()
        val today = images.first()
        assertThat(today.urlBase).isEqualTo("/th?id=OHR.WildTom_EN-US1069249609")
        assertThat(today.copyright).isEqualTo("Two wild tom turkeys (© davidsdodd/Getty Images)")
        assertThat(today.copyrightLink).isEqualTo("https://www.bing.com/search?q=Thanksgiving&form=hpcapt")
    }

    @Test
    fun `non-https credit links are dropped`() {
        assertThat(BingClient.parseArchive(sample)[1].copyrightLink).isNull()
    }

    @Test(expected = JSONException::class)
    fun `missing images array is a parse error`() {
        BingClient.parseArchive("""{"tooltips":{}}""")
    }
}
