#%RAML 1.0
title: ${title}
version: ${version}
mediaType:  application/json
${types}

resourceTypes:
  collection:
    is: [odata.orderby, odata.top, odata.skip, odata.filter, odata.expand, odata.format, odata.select, odata.inlinecount]
    get:
      description: List of <<model | !pluralize>>
      responses:
        200:
          body:
            application/json:
              type: <<model>>[]
    post:
      description: Create a new <<model>>
      body:
        application/json:
          type: <<model>>
      responses:
        200:
          body:
            application/json:
              type: <<model>>
  member:
    is: [odata.filter, odata.expand, odata.format, odata.select]
    get:
      description: Read <<model>>
      responses:
        200:
          body:
            application/json:
              type: <<model>>
    delete:
      description: Delete <<model>>
      responses:
        200:
          body:
            application/json:
              type: <<model>>
    put:
      description: Update <<model>>
      body:
        application/json:
          type: <<model>>
      responses:
        204:
          body:
            application/json:
              type: <<model>>
<#list resources as resource>
/${resource.collectionName}:
  displayName: ${resource.collectionName}
  type: { collection: { model: ${resource.name} } }
  /${resource.id}:
    displayName: ${resource.elementName}
    type: { member: { model: ${resource.name} } }
</#list>