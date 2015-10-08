/*
 * (c) 2003-2015 MuleSoft, Inc. This software is protected under international copyright
 * law. All use of this software is subject to MuleSoft's Master Subscription Agreement
 * (or other master license agreement) separately entered into in writing between you and
 * MuleSoft. If such an agreement is not in place, you may not use the software.
 */
package org.mule.module.apikit.odata;

import org.apache.log4j.Logger;
import org.mule.api.MuleEvent;
import org.mule.api.MuleException;
import org.mule.api.transport.PropertyScope;
import org.mule.module.apikit.Configuration;
import org.mule.module.apikit.HttpRestRequest;
import org.mule.module.apikit.Router;
import org.mule.module.apikit.odata.error.ODataErrorHandler;
import org.mule.module.apikit.odata.exception.ODataException;
import org.mule.module.apikit.odata.formatter.ODataPayloadFormatter.Format;
import org.mule.module.apikit.odata.metadata.GatewayMetadataManager;
import org.mule.module.apikit.odata.metadata.exception.GatewayMetadataEntityNotFoundException;
import org.mule.module.apikit.odata.metadata.exception.GatewayMetadataFieldsException;
import org.mule.module.apikit.odata.metadata.exception.GatewayMetadataFormatException;
import org.mule.module.apikit.odata.metadata.exception.GatewayMetadataResourceNotFound;
import org.mule.module.apikit.odata.processor.ODataRequestProcessor;
import org.mule.module.apikit.spi.Service;
import org.mule.processor.AbstractRequestResponseMessageProcessor;

public class ODataService implements Service {

	private static final String ODATA_SVC_URI_PREFIX = "odata.svc";
	private static final String CONTEXT_INITIALIZED = "contextInitialized";
	
	public boolean evaluateURI(MuleEvent event) {
		String path = event.getMessage().getInboundProperty("http.relative.path");
		return path.contains(ODATA_SVC_URI_PREFIX);
	}
	
	public MuleEvent processBlockingRequest(MuleEvent event, AbstractRequestResponseMessageProcessor abstractRouter) throws MuleException {
		Logger.getLogger(ODataService.class).info("Handling odata enabled request.");
		
		Router router = (Router) abstractRouter; 	
		
		if(event.getMessage().getOutboundProperty(CONTEXT_INITIALIZED) == null){
			
				try {
					initializeModel(event, router);
				} catch (ODataException e) {
					Logger.getLogger(ODataService.class).error(e);
					return ODataErrorHandler.handle(event, e);
				}
			
			event.getMessage().setProperty(CONTEXT_INITIALIZED, true, PropertyScope.OUTBOUND);
		}
		
		return ODataService.processODataRequest(event, router);
	}
	

	protected static void initializeModel (MuleEvent event, Router router) throws GatewayMetadataFieldsException, GatewayMetadataResourceNotFound, GatewayMetadataFormatException, GatewayMetadataEntityNotFoundException {
		Logger.getLogger(ODataService.class).info("Init model.");
		GatewayContextInitializer contextInitializer = new GatewayContextInitializer();
		contextInitializer.initializeContext(event, router.getConfig());
	}
	
	protected static MuleEvent processODataRequest(MuleEvent event, Router router) throws MuleException {
		Format format = null;
		
		try {
					
			Configuration config = router.getConfig();
			
			HttpRestRequest request = new HttpRestRequest(event, config);
			String path = request.getResourcePath();
			String query = event.getMessage().getInboundProperty("http.query.string");

			// Metadata manager setup
			GatewayMetadataManager gatewayMetadataManager = new GatewayMetadataManager();
			
			// URIParser
			ODataRequestProcessor odataRequestProcessor = ODataUriParser.parse(gatewayMetadataManager, path, query);

			// Validate format
			format = ODataFormatHandler.getFormat(event); 

			// Request processor
			ODataPayload odataPayload = odataRequestProcessor.process(event, router, format);

			// Response transformer
			return ODataResponseTransformer.transform(event, odataPayload, format);

		} catch (Exception ex) {			
			return ODataErrorHandler.handle(event, ex, format);
		}
	}

}
