package io.kbrag.infrastructure.auth;

import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.model.ExternalAuthOutcome;
import io.kbrag.domain.model.ExternalIdentity;
import io.kbrag.domain.port.SamlProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * SAML 2.0 service provider on the JDK's own XML digital signature machinery.
 *
 * <p>No SAML library: the SP side of the web browser profile is one outgoing AuthnRequest and one
 * incoming signed Response, and the JDK ships the two hard parts - DOM and XMLDSig - since Java 6.
 * The libraries in this space carry the full protocol surface (metadata exchange, artifact
 * binding, logout) that this deployment does not speak.
 *
 * <p>The trust decision is exactly one check: did the configured IdP certificate sign the element
 * the identity is read from. Everything else - NotOnOrAfter, Audience, InResponseTo - binds that
 * signed statement to this deployment and to the request that asked for it. The signature
 * reference is required to cover the enclosing Response or Assertion by its ID, which is what
 * stops a valid signed assertion from being smuggled inside an attacker shaped envelope.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class XmlDsigSamlProcessor implements SamlProcessor {

    private static final String NS_PROTOCOL = "urn:oasis:names:tc:SAML:2.0:protocol";
    private static final String NS_ASSERTION = "urn:oasis:names:tc:SAML:2.0:assertion";
    private static final String STATUS_SUCCESS = "urn:oasis:names:tc:SAML:2.0:status:Success";
    private static final String BINDING_POST = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST";
    private static final String ATTR_ID = "ID";
    /** Tolerated clock difference against the IdP, seconds. */
    private static final long CLOCK_SKEW_SECONDS = 60;

    private final KbProperties properties;

    @Override
    public boolean available() {
        KbProperties.Auth.Saml saml = properties.getAuth().getSaml();
        return saml.isEnabled()
                && notBlank(saml.getIdpEntityId())
                && notBlank(saml.getIdpSsoUrl())
                && notBlank(saml.getIdpCertificate())
                && notBlank(properties.getAuth().getSso().getWebBaseUrl());
    }

    @Override
    public String loginRedirectUrl(String requestId, String relayState, String acsUrl) {
        KbProperties.Auth.Saml saml = properties.getAuth().getSaml();
        // The redirect binding carries the request deflated (raw, no zlib header), base64 encoded
        // and URL encoded, in that order - each layer undone by the IdP in reverse.
        String request = "<samlp:AuthnRequest"
                + " xmlns:samlp=\"" + NS_PROTOCOL + "\""
                + " xmlns:saml=\"" + NS_ASSERTION + "\""
                + " ID=\"" + requestId + "\""
                + " Version=\"2.0\""
                + " IssueInstant=\"" + Instant.now().truncatedTo(ChronoUnit.SECONDS) + "\""
                + " Destination=\"" + saml.getIdpSsoUrl() + "\""
                + " ProtocolBinding=\"" + BINDING_POST + "\""
                + " AssertionConsumerServiceURL=\"" + acsUrl + "\">"
                + "<saml:Issuer>" + saml.getSpEntityId() + "</saml:Issuer>"
                + "</samlp:AuthnRequest>";
        String encoded = Base64.getEncoder().encodeToString(deflate(request));
        String separator = saml.getIdpSsoUrl().contains("?") ? "&" : "?";
        return saml.getIdpSsoUrl() + separator
                + "SAMLRequest=" + URLEncoder.encode(encoded, StandardCharsets.UTF_8)
                + "&RelayState=" + URLEncoder.encode(relayState, StandardCharsets.UTF_8);
    }

    @Override
    public ExternalAuthOutcome consume(String samlResponse, String expectedRequestId, String acsUrl) {
        PublicKey idpKey;
        try {
            idpKey = parseCertificate(properties.getAuth().getSaml().getIdpCertificate());
        } catch (Exception e) {
            // A certificate that does not parse is a deployment fault, not the caller's: nothing
            // the browser posted can be judged without it.
            log.error("saml idp certificate unreadable, single sign on is effectively down", e);
            return ExternalAuthOutcome.unavailable();
        }
        try {
            Document document = SecureXml.parse(
                    new String(Base64.getDecoder().decode(samlResponse.trim()), StandardCharsets.UTF_8));
            Element response = document.getDocumentElement();
            registerIds(document);

            if (!verdictIsSuccess(response)) {
                log.info("saml response carries a non success status");
                return ExternalAuthOutcome.invalid();
            }
            String inResponseTo = response.getAttribute("InResponseTo");
            if (!expectedRequestId.equals(inResponseTo)) {
                log.info("saml response answers a different request, inResponseTo={}", inResponseTo);
                return ExternalAuthOutcome.invalid();
            }
            Element assertion = firstChild(response, NS_ASSERTION, "Assertion");
            if (assertion == null) {
                // An EncryptedAssertion or none at all; encryption is out of scope by contract, the
                // channel is TLS end to end.
                log.info("saml response carries no plain assertion");
                return ExternalAuthOutcome.invalid();
            }
            if (!signatureCovers(document, idpKey, response, assertion)) {
                log.info("saml response signature rejected");
                return ExternalAuthOutcome.invalid();
            }
            if (!conditionsHold(assertion)) {
                return ExternalAuthOutcome.invalid();
            }
            Element nameId = descendant(assertion, NS_ASSERTION, "NameID");
            if (nameId == null || nameId.getTextContent() == null || nameId.getTextContent().isBlank()) {
                log.info("saml assertion asserts no NameID");
                return ExternalAuthOutcome.invalid();
            }
            return ExternalAuthOutcome.success(
                    new ExternalIdentity(nameId.getTextContent().trim(), null, null));
        } catch (Exception e) {
            // Malformed base64, malformed XML, or anything else the post shaped: all of it is the
            // caller's own doing and counts like a wrong password.
            log.info("saml response rejected: {}", e.getMessage());
            return ExternalAuthOutcome.invalid();
        }
    }

    /**
     * Validates that a signature made by the IdP key covers the Response or the Assertion.
     *
     * <p>Cryptographic validity alone is not enough: a valid signature over some unrelated node
     * would still validate. The reference of the accepted signature must resolve to the ID of one
     * of the two elements the identity is actually read from.
     */
    private boolean signatureCovers(Document document, PublicKey idpKey,
                                    Element response, Element assertion) {
        NodeList signatures = document.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
        String responseRef = "#" + response.getAttribute(ATTR_ID);
        String assertionRef = "#" + assertion.getAttribute(ATTR_ID);
        XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
        for (int i = 0; i < signatures.getLength(); i++) {
            try {
                DOMValidateContext context = new DOMValidateContext(idpKey, signatures.item(i));
                XMLSignature signature = factory.unmarshalXMLSignature(context);
                boolean valid = signature.validate(context);
                if (!valid) {
                    continue;
                }
                boolean coversIdentity = signature.getSignedInfo().getReferences().stream()
                        .anyMatch(reference -> responseRef.equals(reference.getURI())
                                || assertionRef.equals(reference.getURI()));
                if (coversIdentity) {
                    return true;
                }
            } catch (Exception e) {
                log.info("saml signature candidate failed to validate: {}", e.getMessage());
            }
        }
        return false;
    }

    /**
     * Checks the assertion conditions: the validity window and the audience restriction.
     */
    private boolean conditionsHold(Element assertion) {
        Element conditions = firstChild(assertion, NS_ASSERTION, "Conditions");
        if (conditions == null) {
            log.info("saml assertion carries no conditions");
            return false;
        }
        Instant now = Instant.now();
        String notBefore = conditions.getAttribute("NotBefore");
        if (!notBefore.isBlank()
                && now.plusSeconds(CLOCK_SKEW_SECONDS).isBefore(Instant.parse(notBefore))) {
            log.info("saml assertion not yet valid, notBefore={}", notBefore);
            return false;
        }
        String notOnOrAfter = conditions.getAttribute("NotOnOrAfter");
        if (notOnOrAfter.isBlank()
                || !now.minusSeconds(CLOCK_SKEW_SECONDS).isBefore(Instant.parse(notOnOrAfter))) {
            log.info("saml assertion expired or unbounded, notOnOrAfter={}", notOnOrAfter);
            return false;
        }
        Element audience = descendant(conditions, NS_ASSERTION, "Audience");
        String spEntityId = properties.getAuth().getSaml().getSpEntityId();
        if (audience == null || !spEntityId.equals(audience.getTextContent().trim())) {
            // An assertion addressed to some other service must not open a session here, even when
            // the same IdP signed it: that is the whole point of the audience restriction.
            log.info("saml assertion addressed to a different audience");
            return false;
        }
        return true;
    }

    private boolean verdictIsSuccess(Element response) {
        Element statusCode = descendant(response, NS_PROTOCOL, "StatusCode");
        return statusCode != null && STATUS_SUCCESS.equals(statusCode.getAttribute("Value"));
    }

    /**
     * Marks every SAML {@code ID} attribute as an XML id.
     *
     * <p>Signature references are {@code #id} URIs, and a parsed document has no DTD to say which
     * attribute is the id one; without this registration every reference fails to resolve.
     */
    private void registerIds(Document document) {
        NodeList elements = document.getElementsByTagName("*");
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            if (element.hasAttribute(ATTR_ID)) {
                element.setIdAttribute(ATTR_ID, true);
            }
        }
    }

    private PublicKey parseCertificate(String pem) throws Exception {
        String base64 = pem
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(der));
        return certificate.getPublicKey();
    }

    private byte[] deflate(String value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.DEFLATED, true);
        try (DeflaterOutputStream stream = new DeflaterOutputStream(out, deflater)) {
            stream.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            // A byte array stream cannot fail; the checked exception is an artifact of the API.
            throw new IllegalStateException("deflate of an in memory buffer failed", e);
        } finally {
            deflater.end();
        }
        return out.toByteArray();
    }

    private static Element firstChild(Element parent, String namespace, String localName) {
        NodeList children = parent.getElementsByTagNameNS(namespace, localName);
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getParentNode() == parent) {
                return (Element) children.item(i);
            }
        }
        return null;
    }

    private static Element descendant(Element root, String namespace, String localName) {
        NodeList found = root.getElementsByTagNameNS(namespace, localName);
        return found.getLength() == 0 ? null : (Element) found.item(0);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
