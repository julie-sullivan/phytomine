package org.intermine.web.struts;

/*
 * Copyright (C) 2002-2010 FlyMine
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.log4j.Logger;
import org.apache.struts.action.ActionForm;
import org.apache.struts.action.ActionForward;
import org.apache.struts.action.ActionMapping;
import org.apache.struts.action.ActionMessage;
import org.intermine.api.InterMineAPI;
import org.intermine.api.profile.Profile;
import org.intermine.api.template.TemplateQuery;
import org.intermine.api.template.TemplateSummariser;
import org.intermine.objectstore.ObjectStoreException;
import org.intermine.web.logic.session.SessionMethods;

/**
 * Action to summarise all templates.
 *
 * @author Matthew Wakeling
 */
public class SummariseAllTemplatesAction extends InterMineAction
{
    protected static final Logger LOG = Logger.getLogger(CreateTemplateAction.class);

    /**
     * Summarises every template, and then forwards to the mymine template page.
     *
     * @param mapping The ActionMapping used to select this instance
     * @param form The optional ActionForm bean for this request (if any)
     * @param request The HTTP request we are processing
     * @param response The HTTP response we are creating
     * @return an ActionForward object defining where control goes next
     *
     * @exception Exception if the application business logic throws an exception
     */
    public ActionForward execute(ActionMapping mapping,
                                 @SuppressWarnings("unused") ActionForm form,
                                 HttpServletRequest request,
                                 @SuppressWarnings("unused") HttpServletResponse response)
        throws Exception {
        HttpSession session = request.getSession();
        final InterMineAPI im = SessionMethods.getInterMineAPI(session);

        Profile profile = SessionMethods.getProfile(session);
        final TemplateSummariser summariser = im.getTemplateSummariser();

        Map<String, TemplateQuery> templates = profile.getSavedTemplates();
        for (Map.Entry<String, TemplateQuery> entry : templates.entrySet()) {
            //String templateName = entry.getKey();
            TemplateQuery template = entry.getValue();
            try {
                summariser.summarise(template);
            } catch (ObjectStoreException e) {
                recordError(new ActionMessage("errors.query.objectstoreerror"), request, e, LOG);
            }
        }

        return new ForwardParameters(mapping.findForward("mymine"))
            .addParameter("subtab", "templates").forward();
    }
}
