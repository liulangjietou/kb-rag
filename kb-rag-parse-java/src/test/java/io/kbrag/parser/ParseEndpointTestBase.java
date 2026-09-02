package io.kbrag.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kbrag.parser.config.ParserProperties;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * Shared wiring for the endpoint tests: a MockMvc client, the two multipart helpers, and restoration
 * of any runtime property a test tweaked.
 *
 * <p>Properties are mutated on the live bean rather than through a per-test application context. The
 * caps under test - image count, image bytes, OCR engine - are read per parse call precisely so they
 * can be varied at runtime, and spinning up a fresh context for each variation would test a different
 * thing (a differently-configured startup) than the one that matters here.
 *
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class ParseEndpointTestBase {

    protected static final String PARSE_PATH = "/api/v1/parse";
    protected static final String PARSE_CHAT_PATH = "/api/v1/parse/chat";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ParserProperties properties;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void restoreDefaults() {
        ParserProperties defaults = new ParserProperties();
        properties.setMaxImagesPerDoc(defaults.getMaxImagesPerDoc());
        properties.setMaxImageBytes(defaults.getMaxImageBytes());
        properties.setScannedPageTextThreshold(defaults.getScannedPageTextThreshold());
        properties.setGarbledPageValidCharRatioPct(defaults.getGarbledPageValidCharRatioPct());
        properties.setOcrEngine(defaults.getOcrEngine());
    }

    protected JsonNode postParse(String filename, byte[] content, String fileExt) throws Exception {
        return perform(multipart(PARSE_PATH)
                .file(new MockMultipartFile("file", filename, "application/octet-stream", content))
                .param("file_ext", fileExt));
    }

    protected JsonNode postParseChat(String filename, byte[] content, String fileExt) throws Exception {
        return postParseChat(filename, content, fileExt, null, null);
    }

    protected JsonNode postParseChat(String filename, byte[] content, String fileExt,
                                     String mappingProfile, String profileYaml) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart(PARSE_CHAT_PATH)
                .file(new MockMultipartFile("file", filename, "application/octet-stream", content));
        request.param("file_ext", fileExt);
        if (mappingProfile != null) {
            request.param("mapping_profile", mappingProfile);
        }
        if (profileYaml != null) {
            request.param("profile_yaml", profileYaml);
        }
        return perform(request);
    }

    private JsonNode perform(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(200, result.getResponse().getStatus(),
                "the envelope carries the outcome, so the transport status is always 200");
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
