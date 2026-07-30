package io.kbrag.infrastructure.auth;

import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * DOM parsing of XML received from an identity provider, hardened against entity attacks.
 *
 * <p>SAML responses and CAS validation replies are attacker reachable XML: whoever controls the
 * bytes controls what the parser is asked to resolve. Every external entity switch is therefore
 * off - a DOCTYPE in an authentication response has no legitimate reading, only file disclosure
 * and denial of service ones. Kept in one class so the reasoning is not re-derived, and possibly
 * differently, at each protocol adapter.
 *
 * @author owlzhangfq@gmail.com
 */
final class SecureXml {

    private SecureXml() {
    }

    /**
     * Parses a namespace aware document with every external entity feature disabled.
     *
     * @param xml document bytes as text
     * @return parsed document
     * @throws ParserConfigurationException when the runtime parser refuses the hardening switches
     * @throws SAXException                 when the document is not well formed
     * @throws IOException                  never for a byte array source, declared by the parser API
     */
    static Document parse(String xml) throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
