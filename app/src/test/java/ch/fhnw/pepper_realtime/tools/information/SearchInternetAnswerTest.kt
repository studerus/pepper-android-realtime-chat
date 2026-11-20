package ch.fhnw.pepper_realtime.tools.information

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SearchInternetAnswerTest {
    @Test fun `direct answer takes precedence and null is never returned as text`() {
        val tool = SearchInternetTool()
        val data = JSONObject("""{"results":[{"content":null},{"content":"snippet"}]}""")
        assertEquals("snippet", tool.searchAnswer(data))
        for (empty in listOf(JSONObject.NULL, "", "   ")) {
            assertEquals("snippet", tool.searchAnswer(data.put("answer", empty)))
        }
        assertEquals("summary", tool.searchAnswer(data.put("answer", "summary")))
        assertTrue(tool.searchAnswer(JSONObject("""{"answer":null}""")).contains("couldn't find"))
    }
}
