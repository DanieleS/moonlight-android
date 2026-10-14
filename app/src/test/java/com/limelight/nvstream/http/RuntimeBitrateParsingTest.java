package com.limelight.nvstream.http;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.xmlpull.v1.XmlPullParserException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

// The bodies are Vibepollo's own (nvhttp.cpp's setBitrate and getAbrCapabilities, written through
// boost's write_xml and as a JSON literal). Robolectric for Android's org.json and XmlPullParser.
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class RuntimeBitrateParsingTest {

    private static final String XML_HEADER = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n";

    @Test
    public void vibepollosCapabilitiesOfferRuntimeBitrate() {
        assertTrue(NvHTTP.parseRuntimeBitrateSupport(
                "{\"supported\":false,\"version\":1,\"features\":[\"runtime_bitrate\"]}"));
    }

    @Test
    public void aHostDecidingForItselfOffersItToo() {
        assertTrue(NvHTTP.parseRuntimeBitrateSupport("{\"supported\":true,\"version\":1}"));
    }

    @Test
    public void capabilitiesWithoutTheFeatureOfferNothing() {
        assertFalse(NvHTTP.parseRuntimeBitrateSupport("{\"supported\":false,\"version\":1,\"features\":[]}"));
        assertFalse(NvHTTP.parseRuntimeBitrateSupport("{\"supported\":false}"));
    }

    @Test
    public void aBodyThatIsNotJsonOffersNothing() {
        assertFalse(NvHTTP.parseRuntimeBitrateSupport("<!doctype html><html><body>Sunshine</body></html>"));
        assertFalse(NvHTTP.parseRuntimeBitrateSupport(""));
        assertFalse(NvHTTP.parseRuntimeBitrateSupport("[\"runtime_bitrate\"]"));
    }

    @Test
    public void anAppliedBitrateIsRead() throws Exception {
        assertEquals(38988, NvHTTP.parseRuntimeBitrateReply(
                XML_HEADER + "<root status_code=\"200\"><bitrate>38988</bitrate></root>"));
    }

    @Test
    public void noActiveSessionIsARefusalNotAMissingEndpoint() throws Exception {
        try {
            NvHTTP.parseRuntimeBitrateReply(XML_HEADER + "<root status_code=\"404\" "
                    + "status_message=\"No active session for this client\"><bitrate>0</bitrate></root>");
            fail();
        } catch (HostHttpResponseException e) {
            assertEquals(404, e.getErrorCode());
            assertEquals("No active session for this client", e.getErrorMessage());
        }
    }

    @Test
    public void aMissingPermissionIsARefusal() throws Exception {
        try {
            NvHTTP.parseRuntimeBitrateReply(XML_HEADER + "<root status_code=\"403\" "
                    + "status_message=\"Permission denied\"><bitrate>0</bitrate></root>");
            fail();
        } catch (HostHttpResponseException e) {
            assertEquals(403, e.getErrorCode());
        }
    }

    @Test(expected = XmlPullParserException.class)
    public void aReplyWithoutABitrateIsMalformed() throws Exception {
        NvHTTP.parseRuntimeBitrateReply(XML_HEADER + "<root status_code=\"200\"></root>");
    }

    @Test(expected = XmlPullParserException.class)
    public void aBitrateThatIsNotANumberIsMalformed() throws Exception {
        NvHTTP.parseRuntimeBitrateReply(XML_HEADER + "<root status_code=\"200\"><bitrate>fast</bitrate></root>");
    }
}
