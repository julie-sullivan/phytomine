<%@ tag body-content="empty"%>
<%@ attribute name="track" required="true" type="java.lang.Object" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="/WEB-INF/struts-html.tld" prefix="html" %>

<%
org.modmine.web.MetadataCache.GBrowseTrack track = ((org.modmine.web.MetadataCache.GBrowseTrack) jspContext.getAttribute("track")); 
%>

<c:set var="track" value="<%=track%>"/>
<html:link
  href="${WEB_PROPERTIES['gbrowse.prefix']}/${track.organism}/?label=${track.track}/${track.subTrack}" title="View ${track.subTrack} in GBrowse" target="_blank"><c:out value="${track.subTrack}"/>
</html:link>