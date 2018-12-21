/*
 * (c) 2003-2015 MuleSoft, Inc. This software is protected under international copyright
 * law. All use of this software is subject to MuleSoft's Master Subscription Agreement
 * (or other master license agreement) separately entered into in writing between you and
 * MuleSoft. If such an agreement is not in place, you may not use the software.
 */

package org.mule.module.apikit.odata.formatter;

import org.junit.BeforeClass;
import org.junit.Test;
import org.mule.module.apikit.odata.context.OdataContext;
import org.mule.module.apikit.odata.exception.ODataException;
import org.mule.module.apikit.odata.metadata.OdataMetadataManager;

import static org.junit.Assert.assertTrue;
import static org.mule.module.apikit.odata.formatter.ODataPayloadFormatter.InlineCount.NONE;

public class FormatterTestCase {

	private static OdataContext oDataContext;

    @BeforeClass
    public static void setUp() throws ODataException {
        final OdataMetadataManager odataMetadataManager = new OdataMetadataManager("src/test/resources/org/mule/module/apikit/odata/api-mk.raml", true);
        oDataContext = new OdataContext(odataMetadataManager, "GET");
    }

    @Test
    public void serviceDocumentPayloadFormatter() throws Exception {
        OdataMetadataManager odataMetadataManager = oDataContext.getOdataMetadataManager();

        ServiceDocumentPayloadFormatter formatter = new ServiceDocumentPayloadFormatter(odataMetadataManager, "http://localhost:8081/api/odata.svc");

        String format = formatter.format(ODataPayloadFormatter.Format.Atom, NONE);
        assertTrue(format.contains("<collection href=\"customers\">"));
        assertTrue(format.contains("<atom:title>customers</atom:title>"));
        // Entity city pluralized to cities
        assertTrue(format.contains("<collection href=\"cities\">"));
        assertTrue(format.contains("<atom:title>cities</atom:title>"));
    }

    @Test
    public void payloadMetadataFormatter() throws Exception {
        OdataMetadataManager odataMetadataManager = oDataContext.getOdataMetadataManager();

        ODataPayloadMetadataFormatter formatter = new ODataPayloadMetadataFormatter(odataMetadataManager);

        String format = formatter.format(ODataPayloadFormatter.Format.Atom, NONE);
        assertTrue(format.contains("<EntityType Name=\"customers\">"));
        assertTrue(format.contains("<EntityType Name=\"city\">"));
    }
}
