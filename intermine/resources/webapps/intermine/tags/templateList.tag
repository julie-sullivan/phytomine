<%@ tag body-content="empty"  %>

<%@ attribute name="category" required="true" %>
<%@ attribute name="type" required="true" %>
<%@ attribute name="className" required="false" %>
<%@ attribute name="interMineObject" required="false" type="java.lang.Object" %>
<%@ attribute name="important" required="false" type="java.lang.Boolean" %>

<%@ taglib uri="/WEB-INF/struts-html.tld" prefix="html" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/fmt" prefix="fmt" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/functions" prefix="fn" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="im"%>

<c:if test="${type == 'global' && empty className}">
  <c:set var="templates" value="${CATEGORY_TEMPLATES[category]}"/>
</c:if>
<c:if test="${type == 'global' && !empty className}">
  <c:set var="templates" value="${CLASS_CATEGORY_TEMPLATES[className][category]}"/>
</c:if>
<c:if test="${type == 'user'}">
  <c:set var="templates" value="${PROFILE.categoryTemplates[category]}"/>
</c:if>

<c:forEach items="${templates}" var="templateQuery" varStatus="status">
  <%-- filter unimportant templates if necessary --%>
  <c:if test="${!important || templateQuery.important}">
    <im:templateLine type="${type}" templateQuery="${templateQuery}"/>
    <c:if test="${!status.last}">
      <hr class="tmplSeperator"/>
    </c:if>
  </c:if>
</c:forEach>

<c:if test="${empty templates}">
  <div class="altmessage"><fmt:message key="templateList.noTemplates"/></div>
</c:if>


