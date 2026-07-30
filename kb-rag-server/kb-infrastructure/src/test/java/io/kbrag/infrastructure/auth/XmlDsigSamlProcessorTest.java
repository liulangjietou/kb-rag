package io.kbrag.infrastructure.auth;

import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.enums.DirectoryBindResult;
import io.kbrag.domain.model.ExternalAuthOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the SAML service provider of the M16 contract section 3.3 with real XML digital
 * signatures: a static test IdP certificate is configured, its private key signs the assertions
 * built here, and every rejection path - unsigned, foreign key, expired, wrong audience, wrong
 * request, DOCTYPE smuggling - must count like a wrong password while an unreadable certificate is
 * the deployment's own outage.
 *
 * @author owlzhangfq@gmail.com
 */
class XmlDsigSamlProcessorTest {

    private static final String NS_PROTOCOL = "urn:oasis:names:tc:SAML:2.0:protocol";
    private static final String NS_ASSERTION = "urn:oasis:names:tc:SAML:2.0:assertion";
    private static final String STATUS_SUCCESS = "urn:oasis:names:tc:SAML:2.0:status:Success";
    private static final String STATUS_DENIED = "urn:oasis:names:tc:SAML:2.0:status:AuthnFailed";
    private static final String REQUEST_ID = "req_1";
    private static final String SP_ENTITY_ID = "kb-rag";
    private static final String ACS_URL = "https://kb.example.com/sso/saml/acs";

    /** Self signed test IdP certificate; its private key is {@link #IDP_KEY_PKCS8}. */
    private static final String IDP_CERTIFICATE = """
            -----BEGIN CERTIFICATE-----
            MIIDFTCCAf2gAwIBAgIUXlg+O5yWIhtN4DyOHTPbIsi8GxwwDQYJKoZIhvcNAQEL
            BQAwGjEYMBYGA1UEAwwPa2ItcmFnLXRlc3QtaWRwMB4XDTI2MDcyOTIzNDQwOFoX
            DTM2MDcyNjIzNDQwOFowGjEYMBYGA1UEAwwPa2ItcmFnLXRlc3QtaWRwMIIBIjAN
            BgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAxNR7gcx3uqm0oJtsOK82l18IUO0z
            C/PET+riRd9lTaNm95kTm2c3DiPP6SkqojU/Ug2lktNTqli7wtD8zpRUAdT5FKDu
            ZwOYveNDiTu9kRfPsFuy0Gv7eEoo8ex0Bpi/H1pkEN+VWKp6yoqw/IeFg34TFnSM
            EYvK+4xDyL0Yp4XAIL8I5aLTvQSjhKS8AP8iMc6ubRJFd4smgcgnXsqq74C6K+/k
            hfn8euP9pWNlZyMPHbGiBB7TVnWETwNu1ybH7ncvQFk3SAwr2dFUV+hd1/idEQQK
            1OBwXBGZiAxMJYpRlWVFsIIOiVHzKUFre0fJ0Kfqv0o3HhJb53e9uq2TxwIDAQAB
            o1MwUTAdBgNVHQ4EFgQURel8iklJ0qWzQqRsDmfwlt/Jr+0wHwYDVR0jBBgwFoAU
            Rel8iklJ0qWzQqRsDmfwlt/Jr+0wDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0B
            AQsFAAOCAQEAEXn9W/27h4gyQyX0KXOnMIABXojzg6vMMkSo/di6DDkNIsvRHthf
            iv/C6ZUQuNQeub6XQkuvMYq0dVD8SXH7KAnVEayN3jRXDbFADqlg0wSFYuchIzT7
            vawEgrkCUoRFXFpNxhIfvTmROknQ4J2pktBq22kttg5xtCYBJ4HEwHzXx6y+598t
            qpkHujdmk18zkaoGmjjVQBl6vzGdG3yd4/Ou/zZ+N1KsRguyYKKlsXeC3u8/RHmw
            XG8aZR952ego0OYs0MtsST4wYoFCm+hEVsbMUZwRo1L1Le9CnDOw0P2uPWiNcwEO
            8PM17QaP8ghOo5+tx1A3vsx1iKshbVQf4A==
            -----END CERTIFICATE-----""";

    /** PKCS#8 private key of {@link #IDP_CERTIFICATE}, test material only. */
    private static final String IDP_KEY_PKCS8 = """
            MIIEvwIBADANBgkqhkiG9w0BAQEFAASCBKkwggSlAgEAAoIBAQDE1HuBzHe6qbSg
            m2w4rzaXXwhQ7TML88RP6uJF32VNo2b3mRObZzcOI8/pKSqiNT9SDaWS01OqWLvC
            0PzOlFQB1PkUoO5nA5i940OJO72RF8+wW7LQa/t4Sijx7HQGmL8fWmQQ35VYqnrK
            irD8h4WDfhMWdIwRi8r7jEPIvRinhcAgvwjlotO9BKOEpLwA/yIxzq5tEkV3iyaB
            yCdeyqrvgLor7+SF+fx64/2lY2VnIw8dsaIEHtNWdYRPA27XJsfudy9AWTdIDCvZ
            0VRX6F3X+J0RBArU4HBcEZmIDEwlilGVZUWwgg6JUfMpQWt7R8nQp+q/SjceElvn
            d726rZPHAgMBAAECggEAFo4a0RbzKWrFDhqBXkWFxfbX5x4nWVlHx7is0UD2RN1S
            sVDTVF2Ri4dDDf7vqcLcTLTPonGhBsZATeTQ84M/1S3olRqT9y4MVMY0OQelg3jt
            DdKUPRoCqRgmdQKZkR/z1s3u5ZgZbx2qEIewHGATRqwt18bnPiN80TKTme9BYgDY
            IsTUB9/WXLf/Zq6EHU6So6Y+pqXHNihi2OvtB6OhShpWso2vf6SxFxqSgprkEqNE
            B/eBaO1KGVx+QZvPVlcVt640iTyB0zH+5YNrXIetloVPv87yRHQ0BC2LzNtaFXXg
            ArEGMugPQa1hYsa2Yk8OvuhzkJBke5rr1ts6pNmDEQKBgQDvsOV4tEEVkGV5U0Y5
            XR/7fLbOJ0+Rmz1wWmM9qIxlwNB+QBOc7Y46IaLZgllV5e4FQIv0oc1MQs2TR4zm
            jXbC5165HlAMPxw8owGodk73vjoj7SsgoDEvxJKcC4+GEOgq1q6wQQbGpAU8zplG
            bVfuzUQicDCRtbnYruSDXM/4owKBgQDSOQDxC/Uy8MBLTZjGAPnyNFjnz0GmkYdJ
            KhhI99+/OeHFQ9zBT+AcNhZXBic3eMajoFmmAZ8Tog4U9E2psFiMVJ0xOe4Q7wlc
            YVeVhXVUb67aDDwbp1cVw7BZpWv4b07+tNxSoCZ28inFJJcPrjy4ykktg/AXZcZ/
            wGm52kf2jQKBgQDhI3GHfRidHrKR0WxmuVgvKqey4C3XANAm1l/dLJIjiYbM73b8
            sg4kADAsykkLbBu0hzpuoARsG1tpeY2ZiUsCK71HeHeL6UOmmR4XlHj8L4wA7ubR
            kGZjDer/88PuE7dfdaNEHvA0aSAaS5yhEFfGELxs9KBKXT2hkDVIRkd6rQKBgQDQ
            OJf/KVnv39iHfc9xZ6wqQ8E/seCT7Jc0V9aAB8x96wX9zs7MRqJzLvuHrNeRMTaH
            AZ9qNzbqSlxqUuTNy5aZQIIGLRvpd+osr1oCBpOFU6272dx7g49VRmPZF2lPRGjZ
            DikgSfJvZEMNOsXJSUppuisrQwC/HBa1mM7fwB4BTQKBgQCWZ1NNH6dWxK9rZ1ji
            QuHr3Cy2BnDLuHffalN1NfJ8LTqRmyu4WHTjckh+O4ZbMgnxtuhwhTjyJ8cbcZSW
            r5lKt7vjBMCX8JMQ0MXEdTT8aD9RBYTkhQ+HGcByxzLSCjo1dmY0tnd4wAIm2JA/
            zyIEGje42QYFnhB2rHAi6AY3AQ==""";

    private KbProperties properties;
    private XmlDsigSamlProcessor processor;

    @BeforeEach
    void setUp() {
        properties = new KbProperties();
        KbProperties.Auth.Saml saml = properties.getAuth().getSaml();
        saml.setEnabled(true);
        saml.setIdpEntityId("https://idp.example.com");
        saml.setIdpSsoUrl("https://idp.example.com/sso");
        saml.setIdpCertificate(IDP_CERTIFICATE);
        saml.setSpEntityId(SP_ENTITY_ID);
        properties.getAuth().getSso().setWebBaseUrl("https://kb.example.com");
        processor = new XmlDsigSamlProcessor(properties);
    }

    @Test
    void shouldAcceptAnAssertionSignedByTheConfiguredIdp() throws Exception {
        String response = encode(signedResponse(STATUS_SUCCESS, REQUEST_ID,
                Instant.now().plus(10, ChronoUnit.MINUTES), SP_ENTITY_ID, "alice", idpKey()));

        ExternalAuthOutcome outcome = processor.consume(response, REQUEST_ID, ACS_URL);

        assertThat(outcome.result()).isEqualTo(DirectoryBindResult.SUCCESS);
        assertThat(outcome.identity().username()).isEqualTo("alice");
    }

    @Test
    void shouldRejectAnUnsignedResponse() throws Exception {
        String response = encode(signedResponse(STATUS_SUCCESS, REQUEST_ID,
                Instant.now().plus(10, ChronoUnit.MINUTES), SP_ENTITY_ID, "alice", null));

        assertThat(processor.consume(response, REQUEST_ID, ACS_URL).result())
                .isEqualTo(DirectoryBindResult.INVALID_CREDENTIALS);
    }

    @Test
    void shouldRejectASignatureMadeByAForeignKey() throws Exception {
        // Cryptographically valid, just not by the configured IdP: exactly the forgery the single
        // configured trust anchor exists to refuse.
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String response = encode(signedResponse(STATUS_SUCCESS, REQUEST_ID,
                Instant.now().plus(10, ChronoUnit.MINUTES), SP_ENTITY_ID, "alice",
                generator.generateKeyPair().getPrivate()));

        assertThat(processor.consume(response, REQUEST_ID, ACS_URL).result())
                .isEqualTo(DirectoryBindResult.INVALID_CREDENTIALS);
    }

    @Test
    void shouldRejectAnExpiredAssertion() throws Exception {
        String response = encode(signedResponse(STATUS_SUCCESS, REQUEST_ID,
                Instant.now().minus(10, ChronoUnit.MINUTES), SP_ENTITY_ID, "alice", idpKey()));

        assertThat(processor.consume(response, REQUEST_ID, ACS_URL).result())
                .isEqualTo(DirectoryBindResult.INVALID_CREDENTIALS);
    }

    @Test
    void shouldRejectAnAssertionAddressedToAnotherAudience() throws Exception {
        // Signed by the right IdP but for some other service: the audience restriction is what
        // keeps one IdP from being a master key across its service providers.
        String response = encode(signedResponse(STATUS_SUCCESS, REQUEST_ID,
                Instant.now().plus(10, ChronoUnit.MINUTES), "some-other-sp", "alice", idpKey()));

        assertThat(processor.consume(response, REQUEST_ID, ACS_URL).result())
                .isEqualTo(DirectoryBindResult.INVALID_CREDENTIALS);
    }

    @Test
    void shouldRejectAResponseAnsweringAnotherRequest() throws Exception {
        String response = encode(signedResponse(STATUS_SUCCESS, "req_someone_elses",
                Instant.now().plus(10, ChronoUnit.MINUTES), SP_ENTITY_ID, "alice", idpKey()));

        assertThat(processor.consume(response, REQUEST_ID, ACS_URL).result())
                .isEqualTo(DirectoryBindResult.INVALID_CREDENTIALS);
    }

    @Test
    void shouldRejectAResponseCarryingADoctype() {
        // A DOCTYPE in an authentication response has no legitimate reading, only file disclosure
        // and denial of service ones; the hardened parser must refuse it outright.
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<samlp:Response xmlns:samlp=\"" + NS_PROTOCOL + "\" ID=\"r\">&x;</samlp:Response>";

        assertThat(processor.consume(encode(xxe), REQUEST_ID, ACS_URL).result())
                .isEqualTo(DirectoryBindResult.INVALID_CREDENTIALS);
    }

    @Test
    void shouldRejectANonSuccessVerdict() throws Exception {
        String response = encode(signedResponse(STATUS_DENIED, REQUEST_ID,
                Instant.now().plus(10, ChronoUnit.MINUTES), SP_ENTITY_ID, "alice", idpKey()));

        assertThat(processor.consume(response, REQUEST_ID, ACS_URL).result())
                .isEqualTo(DirectoryBindResult.INVALID_CREDENTIALS);
    }

    @Test
    void shouldReportAnUnreadableCertificateAsAnOutage() {
        // A certificate that does not parse is the deployment's own fault; nothing the browser
        // posted can be judged without it, and the caller must not be counted towards a lockout.
        properties.getAuth().getSaml().setIdpCertificate("not a certificate");

        assertThat(processor.consume(encode("<r/>"), REQUEST_ID, ACS_URL).result())
                .isEqualTo(DirectoryBindResult.SERVICE_UNAVAILABLE);
    }

    @Test
    void shouldCarryTheRequestIdAndRelayStateInTheLoginRedirect() {
        String redirect = processor.loginRedirectUrl(REQUEST_ID, "state-1", ACS_URL);

        assertThat(redirect).startsWith("https://idp.example.com/sso?SAMLRequest=");
        assertThat(redirect).contains("&RelayState=state-1");
    }

    /**
     * Builds a SAML Response whose Assertion is optionally signed with the given key, serialised
     * to the wire form the ACS endpoint receives.
     */
    private String signedResponse(String status, String inResponseTo, Instant notOnOrAfter,
                                  String audience, String nameId, PrivateKey key) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().newDocument();

        Element response = document.createElementNS(NS_PROTOCOL, "samlp:Response");
        response.setAttribute("ID", "resp_1");
        response.setAttribute("InResponseTo", inResponseTo);
        document.appendChild(response);

        Element statusElement = document.createElementNS(NS_PROTOCOL, "samlp:Status");
        Element statusCode = document.createElementNS(NS_PROTOCOL, "samlp:StatusCode");
        statusCode.setAttribute("Value", status);
        statusElement.appendChild(statusCode);
        response.appendChild(statusElement);

        Element assertion = document.createElementNS(NS_ASSERTION, "saml:Assertion");
        assertion.setAttribute("ID", "asrt_1");
        response.appendChild(assertion);

        Element subject = document.createElementNS(NS_ASSERTION, "saml:Subject");
        Element name = document.createElementNS(NS_ASSERTION, "saml:NameID");
        name.setTextContent(nameId);
        subject.appendChild(name);
        assertion.appendChild(subject);

        Element conditions = document.createElementNS(NS_ASSERTION, "saml:Conditions");
        conditions.setAttribute("NotOnOrAfter", notOnOrAfter.truncatedTo(ChronoUnit.SECONDS).toString());
        Element restriction = document.createElementNS(NS_ASSERTION, "saml:AudienceRestriction");
        Element audienceElement = document.createElementNS(NS_ASSERTION, "saml:Audience");
        audienceElement.setTextContent(audience);
        restriction.appendChild(audienceElement);
        conditions.appendChild(restriction);
        assertion.appendChild(conditions);

        if (key == null) {
            return serialize(document);
        }
        // Sign after a serialise/re-parse round trip so the namespace declarations the serialiser
        // adds are already attribute nodes when the digest is taken - the exact tree the verifier
        // will canonicalise, byte for byte.
        Document reparsed = factory.newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(
                        serialize(document).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Element target = (Element) reparsed
                .getElementsByTagNameNS(NS_ASSERTION, "Assertion").item(0);
        // The reference must resolve by ID during signing, same registration the verifier does.
        target.setIdAttribute("ID", true);
        XMLSignatureFactory signatureFactory = XMLSignatureFactory.getInstance("DOM");
        Reference reference = signatureFactory.newReference("#asrt_1",
                signatureFactory.newDigestMethod(DigestMethod.SHA256, null),
                List.of(signatureFactory.newTransform(Transform.ENVELOPED,
                                (TransformParameterSpec) null),
                        signatureFactory.newTransform(CanonicalizationMethod.EXCLUSIVE,
                                (TransformParameterSpec) null)),
                null, null);
        SignedInfo signedInfo = signatureFactory.newSignedInfo(
                signatureFactory.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE,
                        (C14NMethodParameterSpec) null),
                signatureFactory.newSignatureMethod(SignatureMethod.RSA_SHA256, null),
                List.of(reference));
        signatureFactory.newXMLSignature(signedInfo, null)
                .sign(new DOMSignContext(key, target));
        return serialize(reparsed);
    }

    private String serialize(Document document) throws Exception {
        StringWriter writer = new StringWriter();
        TransformerFactory.newInstance().newTransformer()
                .transform(new DOMSource(document), new StreamResult(writer));
        return writer.toString();
    }

    private PrivateKey idpKey() throws Exception {
        byte[] der = Base64.getMimeDecoder().decode(IDP_KEY_PKCS8);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private String encode(String xml) {
        return Base64.getEncoder().encodeToString(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
