/*
 * (c) 2003-2015 MuleSoft, Inc. This software is protected under international copyright law. All
 * use of this software is subject to MuleSoft's Master Subscription Agreement (or other master
 * license agreement) separately entered into in writing between you and MuleSoft. If such an
 * agreement is not in place, you may not use the software.
 */
package org.mule.module.apikit.odata.processor;

import org.mule.extension.http.api.HttpRequestAttributes;
import org.mule.extension.http.api.HttpRequestAttributesBuilder;
import org.mule.module.apikit.odata.AbstractRouterInterface;
import org.mule.module.apikit.odata.ODataPayload;
import org.mule.module.apikit.odata.context.OdataContext;
import org.mule.module.apikit.odata.exception.ClientErrorException;
import org.mule.module.apikit.odata.exception.ODataInvalidFlowResponseException;
import org.mule.module.apikit.odata.exception.ODataInvalidUriException;
import org.mule.module.apikit.odata.exception.ODataUnsupportedMediaTypeException;
import org.mule.module.apikit.odata.formatter.ODataApiKitFormatter;
import org.mule.module.apikit.odata.formatter.ODataPayloadFormatter;
import org.mule.module.apikit.odata.formatter.ODataPayloadFormatter.Format;
import org.mule.module.apikit.odata.metadata.OdataMetadataManager;
import org.mule.module.apikit.odata.metadata.exception.OdataMetadataEntityNotFoundException;
import org.mule.module.apikit.odata.metadata.exception.OdataMetadataFieldsException;
import org.mule.module.apikit.odata.metadata.exception.OdataMetadataFormatException;
import org.mule.module.apikit.odata.metadata.model.entities.EntityDefinition;
import org.mule.module.apikit.odata.metadata.model.entities.EntityDefinitionProperty;
import org.mule.module.apikit.odata.model.Entry;
import org.mule.module.apikit.odata.util.CoreEventUtils;
import org.mule.module.apikit.odata.util.Helper;
import org.mule.module.apikit.odata.util.ODataUriHelper;
import org.mule.runtime.api.message.Message;
import org.mule.runtime.api.metadata.MediaType;
import org.mule.runtime.api.util.MultiMap;
import org.mule.runtime.core.api.event.CoreEvent;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import static com.google.common.base.Strings.isNullOrEmpty;
import static org.mule.module.apikit.odata.formatter.ODataPayloadFormatter.InlineCount.ALL_PAGES;
import static org.mule.module.apikit.odata.formatter.ODataPayloadFormatter.InlineCount.NONE;

public class ODataApikitProcessor extends ODataRequestProcessor {
  private static final String[] methodsWithBody = {"POST", "PUT"};
  public static final String KEY_VALUE_SEPARATOR = "_";
  public static final String KEYS_SEPARATOR = "-";
  public static final String URL_RESOURCE_SEPARATOR = "/";

  private String path;
  private String query;
  private String entity;
  private boolean entityCount;
  // TODO this attr is not used in project
  private Map<String, Object> keys;

  public ODataApikitProcessor(OdataContext odataContext, String entity, String query,
      Map<String, Object> keys, boolean count) throws ODataInvalidUriException {
    super(odataContext);
    this.query = query;
    this.entity = entity;
    this.entityCount = count;
    this.keys = keys;
    this.path = generatePath(entity, keys);
  }

  private static int checkResponseHttpStatus(CoreEvent response) throws ClientErrorException {

    String status = response.getVariables().get("httpStatus").getValue().toString();
    int httpStatus = 0;

    try {
      httpStatus = Integer.valueOf(status);
    } catch (Exception e) {
      // do nothing...
    }

    if (httpStatus >= 400) {
      // If there was an error, it makes no sense to return a list of entry
      // just raise an exception
      Object payload = CoreEventUtils.getPayloadAsString(response);
      throw new ClientErrorException(payload != null ? payload.toString() : "", httpStatus);
    }

    return httpStatus;
  }

  private static ODataPayloadFormatter.InlineCount getInlineCountParam(CoreEvent event) {
    HttpRequestAttributes attributes =
        ((HttpRequestAttributes) event.getMessage().getAttributes().getValue());
    if (attributes == null) {
      return NONE;
    }
    final MultiMap<String, String> queryParams = attributes.getQueryParams();

    final String inlineCountParameterValue = queryParams.get("$inlinecount");

    if ("allpages".equalsIgnoreCase(inlineCountParameterValue)) {
      return ALL_PAGES;
    }

    return NONE;
  }

  private String generatePath(String entity, Map<String, Object> keys)
      throws ODataInvalidUriException {
    String keysPath = URL_RESOURCE_SEPARATOR + entity;
    if (keys.size() == 1) {
      keysPath =
          keysPath + URL_RESOURCE_SEPARATOR + encode(keys.values().iterator().next().toString());
    } else if (keys.size() > 1) {
      Function<Map.Entry<String, Object>, String> parseKey =
          entry -> entry.getKey() + KEY_VALUE_SEPARATOR + entry.getValue();
      keysPath = keysPath + URL_RESOURCE_SEPARATOR + keys.entrySet().stream().map(parseKey).sorted()
          .collect(Collectors.joining(KEYS_SEPARATOR));
    }
    return keysPath;
  }

  private String encode(String id) throws ODataInvalidUriException {
    try {
      return URLEncoder.encode(id, StandardCharsets.UTF_8.name());
    } catch (UnsupportedEncodingException e) {
      throw new ODataInvalidUriException("Unable to encode entity id");
    }
  }

  @Override
  public ODataPayload process(CoreEvent event, AbstractRouterInterface router, List<Format> formats)
      throws Exception {
    String oDataURL = getCompleteUrl(CoreEventUtils.getHttpRequestAttributes(event));

    // truncate the URL at the entity
    oDataURL = ODataUriHelper.getOdataUrl(oDataURL);

    // invoke flow and validate response
    ODataPayload<List<Entry>> oDataPayload = processEntityRequest(event, router, formats);
    final List<Entry> entries = oDataPayload.getValue();

    if (isEntityCount()) {
      if (formats.contains(Format.Plain) || formats.contains(Format.Default)) {
        String count = String.valueOf(entries.size());
        return new ODataPayload<String>(oDataPayload.getMuleEvent(), count,
            oDataPayload.getStatus());
      } else {
        throw new ODataUnsupportedMediaTypeException("Unsupported media type requested.");
      }
    } else {
      oDataPayload.setFormatter(new ODataApiKitFormatter(getMetadataManager(), entity, oDataURL,
          entries, oDataPayload.getInlineCount(getInlineCountParam(event))));
    }

    return oDataPayload;
  }

  public ODataPayload<List<Entry>> processEntityRequest(CoreEvent event,
      AbstractRouterInterface router, List<Format> formats) throws Exception {
    HttpRequestAttributes attributes = CoreEventUtils.getHttpRequestAttributes(event);
    HttpRequestAttributes httpRequestAttributes = getHttpRequestAttributes(attributes);

    Message message;

    if (Arrays.asList(methodsWithBody).contains(attributes.getMethod().toUpperCase())) {
      String payloadAsString = CoreEventUtils.getPayloadAsString(event);
      if (payloadAsString == null) {
        payloadAsString = "";
      }
      boolean isXMLFormat = !formats.contains(Format.Json);
      payloadAsString = BodyToJsonConverter.convertPayload(entity, isXMLFormat, payloadAsString);
      message = Message.builder().value(payloadAsString).mediaType(MediaType.APPLICATION_JSON)
          .attributesValue(httpRequestAttributes).build();
    } else {
      message = Message.builder(event.getMessage()).attributesValue(httpRequestAttributes).build();
    }

    final CoreEvent odataEvent = CoreEvent.builder(event).message(message)
        .addVariable("odata", this.getMetadataManager().getOdataContextVariables(entity)).build();

    Publisher<CoreEvent> processResponse = router.processEvent(odataEvent);
    final CoreEvent response = Mono.from(processResponse)
        .onErrorMap(error -> new ODataInvalidFlowResponseException(error.getMessage())).block();

    return verifyFlowResponse(response);
  }

  private HttpRequestAttributes getHttpRequestAttributes(HttpRequestAttributes attributes) {
    String uri = attributes.getRelativePath();
    String basePath = uri.substring(0, uri.toLowerCase().indexOf("/odata.svc"));
    String httpRequest = basePath + this.path + "?" + this.query;
    String httpRequestPath = basePath + this.path;

    HttpRequestAttributesBuilder httpRequestAttributesBuilder = new HttpRequestAttributesBuilder();
    httpRequestAttributesBuilder.listenerPath(attributes.getListenerPath());
    httpRequestAttributesBuilder.relativePath(this.path);
    httpRequestAttributesBuilder.version(attributes.getVersion());
    httpRequestAttributesBuilder.scheme(attributes.getScheme());
    httpRequestAttributesBuilder.method(attributes.getMethod());
    httpRequestAttributesBuilder.requestPath(httpRequestPath);
    httpRequestAttributesBuilder.requestUri(httpRequest);
    httpRequestAttributesBuilder.queryString(this.query);
    httpRequestAttributesBuilder.localAddress(attributes.getLocalAddress());
    httpRequestAttributesBuilder.remoteAddress(attributes.getRemoteAddress());

    MultiMap<String, String> httpQueryParams = Helper.queryToMap(query);
    httpRequestAttributesBuilder.queryParams(httpQueryParams);

    MultiMap<String, String> headers = new MultiMap<>();
    headers.put("host", attributes.getRemoteAddress());
    headers.put("content-type", MediaType.APPLICATION_JSON.toString());
    httpRequestAttributesBuilder.headers(headers);
    return httpRequestAttributesBuilder.build();
  }

  private ODataPayload<List<Entry>> verifyFlowResponse(CoreEvent event)
      throws OdataMetadataEntityNotFoundException, OdataMetadataFieldsException,
      OdataMetadataFormatException, ODataInvalidFlowResponseException, ClientErrorException {

    int httpStatus = checkResponseHttpStatus(event);
    OdataMetadataManager metadataManager = getMetadataManager();
    EntityDefinition entityDefinition = metadataManager.getEntityByName(entity);
    List<Entry> entries;

    String payload = CoreEventUtils.getPayloadAsString(event);


    if (isNullOrEmpty(payload)) {
      entries = new ArrayList<>();
    } else {
      entries = Helper.transformJsonToEntryList(payload);
    }
    int entryNumber = 1;
    for (Entry entry : entries) {
      // verifies the # of properties matches the definition
      if (entry.getProperties().entrySet().size() > entityDefinition.getProperties().size()) {
        throw new ODataInvalidFlowResponseException(
            "There are absent properties in flow response (entry #" + (entryNumber++) + ")");
      }
      // verifies each property against the definition
      for (String propertyName : entry.getProperties().keySet()) {
        EntityDefinitionProperty entityDefinitionProperty =
            entityDefinition.findPropertyDefinition(propertyName);
        if (entityDefinitionProperty == null) {
          throw new ODataInvalidFlowResponseException("Property '" + propertyName
              + "' was not expected for entity '" + entityDefinition.getName() + "'");
        }
      }
    }

    return new ODataPayload<>(event, entries, httpStatus);
  }

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }

  public String getQuery() {
    return query;
  }

  public void setQuery(String query) {
    this.query = query;
  }

  public boolean isEntityCount() {
    return entityCount;
  }

  public Map<String, Object> getKeys() {
    return keys;
  }

  public void setKeys(Map<String, Object> keys) {
    this.keys = keys;
  }
}
