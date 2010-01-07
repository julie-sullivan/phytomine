package org.intermine.web.logic.session;

/*
 * Copyright (C) 2002-2010 FlyMine
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.commons.collections.map.LRUMap;
import org.apache.log4j.Logger;
import org.apache.struts.util.MessageResources;
import org.intermine.api.InterMineAPI;
import org.intermine.api.profile.InterMineBag;
import org.intermine.api.profile.Profile;
import org.intermine.api.profile.ProfileManager;
import org.intermine.api.profile.SavedQuery;
import org.intermine.api.query.WebResultsExecutor;
import org.intermine.api.results.WebResults;
import org.intermine.api.search.SearchRepository;
import org.intermine.api.template.TemplateQuery;
import org.intermine.api.util.NameUtil;
import org.intermine.metadata.Model;
import org.intermine.model.InterMineObject;
import org.intermine.objectstore.ObjectStore;
import org.intermine.objectstore.ObjectStoreException;
import org.intermine.objectstore.ObjectStoreQueryDurationException;
import org.intermine.objectstore.intermine.ObjectStoreInterMineImpl;
import org.intermine.objectstore.query.Query;
import org.intermine.pathquery.Path;
import org.intermine.pathquery.PathQuery;
import org.intermine.web.autocompletion.AutoCompleter;
import org.intermine.web.logic.Constants;
import org.intermine.web.logic.aspects.Aspect;
import org.intermine.web.logic.config.WebConfig;
import org.intermine.web.logic.pathqueryresult.PathQueryResultHelper;
import org.intermine.web.logic.query.PageTableQueryMonitor;
import org.intermine.web.logic.query.QueryMonitor;
import org.intermine.web.logic.results.DisplayObjectFactory;
import org.intermine.web.logic.results.PagedTable;
import org.intermine.web.logic.results.WebState;
import org.intermine.web.logic.template.TemplateBuildState;
import org.intermine.web.struts.LoadQueryAction;
import org.intermine.web.struts.TemplateAction;

/**
 * Business logic that interacts with session data. These methods are generally
 * called by actions to perform business logic. Obviously, a lot of the business
 * logic happens in other parts of intermine, called from here, then the users
 * session is updated accordingly.
 *
 * @author  Thomas Riley
 */
public class SessionMethods
{
    private interface CompletionCallBack
    {
        void complete();
    }

    private interface Action
    {
        void process();
    }

    protected static final Logger LOG = Logger.getLogger(SessionMethods.class);
    private static int topQueryId = 0;
    private static int index = 0;

    /**
     * Base class for query thread runnable.
     */
    private abstract static class RunQueryThread implements Runnable
    {
        protected boolean done = false;
        protected boolean error = false;

        protected boolean isDone() {
            return done;
        }
        protected boolean isError() {
            return error;
        }
    }

    /**
     * Executes an action and call a callback when it completes successfully. If the
     * query fails for some reason, this method returns false and ActionErrors are set on the
     * request.
     *
     * @param session   the http session
     * @param resources message resources
     * @param qid       the query id
     * @param action    the action/query to perform in a new thread
     * @param completionCallBack sets the method to call when the action successfully completes
     * @return  true if query ran successfully, false if an error occured
     * @throws  Exception if getting results info from paged results fails
     */
    public static boolean runQuery(final HttpSession session,
                                   final MessageResources resources,
                                   final String qid,
                                   final Action action,
                                   final CompletionCallBack completionCallBack)
        throws Exception {
        final InterMineAPI im = getInterMineAPI(session);
        final ObjectStore os = im.getObjectStore();

        final ObjectStoreInterMineImpl ios;
        if (os instanceof ObjectStoreInterMineImpl) {
            ios = (ObjectStoreInterMineImpl) os;
        } else {
            ios = null;
        }
        Map queries = (Map) session.getAttribute(Constants.RUNNING_QUERIES);
        QueryMonitor monitor = (QueryMonitor) queries.get(qid);
        // A reference to this runnable is used as a token for registering
        // a cancelling the running query

        RunQueryThread runnable = new RunQueryThread() {
            public void run () {
                try {

                    // Register request id for query on this thread
                    // We do this before setting r
                    if (ios != null) {
                        LOG.debug("Registering request id " + this);
                        ios.registerRequestId(this);
                    }

                    // call this so that if an exception occurs we notice now rather than in the
                    // JSP code
                    try {
                        action.process();
                    } catch (IndexOutOfBoundsException err) {
                        // no results - ignore
                        // we don't call size() first to avoid this exception because that could be
                        // very slow on a large results set
                    } catch (RuntimeException e) {
                        if (e.getCause() instanceof ObjectStoreException) {
                            throw (ObjectStoreException) e.getCause();
                        }
                        throw e;
                    }
                } catch (ObjectStoreException e) {
                    // put stack trace in the log
                    LOG.error("Exception", e);

                    String key = (e instanceof ObjectStoreQueryDurationException)
                        ? "errors.query.estimatetimetoolong"
                        : "errors.query.objectstoreerror";
                    recordError(resources.getMessage(key), session);

                    error = true;
                } catch (Throwable err) {
                    StringWriter sw = new StringWriter();
                    err.printStackTrace(new PrintWriter(sw));
                    recordError(sw.toString(), session);
                    LOG.error("Exception", err);
                    error = true;
                } finally {
                    try {
                        LOG.debug("Deregistering request id " + this);
                        ((ObjectStoreInterMineImpl) os).deregisterRequestId(this);
                    } catch (ObjectStoreException e1) {
                        LOG.error("Exception", e1);
                        error = true;
                    }
                }
            }
        };
        Thread thread = null;
        thread = new Thread(runnable);
        thread.start();

        while (thread.isAlive()) {
            Thread.sleep(1000);
            if (monitor != null) {
                boolean cancelled = monitor.shouldCancelQuery();
                if (cancelled && ios != null) {
                    LOG.debug("Cancelling request " + runnable);
                    ios.cancelRequest(runnable);
                    monitor.queryCancelled();
                    return false;
                }
            }
        }

        if (runnable.isError()) {
            if (monitor != null) {
                monitor.queryCancelledWithError();
            }
            return false;
        }

        if (completionCallBack != null) {
            completionCallBack.complete();
        }

        if (monitor != null) {
            monitor.queryCompleted();
        }

        return true;
    }

    /**
     * Load a query into the session, cloning to avoid modifying the original
     * @param query the query
     * @param session the session
     * @param response the response
     */
    public static void loadQuery(PathQuery query, HttpSession session,
            HttpServletResponse response) {
        // Depending on the class, load PathQuery or TemplateQuery
        if (query instanceof TemplateQuery) {
            TemplateQuery template = (TemplateQuery) query;
            session.setAttribute(Constants.QUERY, template.clone());
            session.setAttribute(Constants.TEMPLATE_BUILD_STATE, new TemplateBuildState(template));
        } else {
            // at the moment we can only load queries that have saved using the
            // webapp
            // this is because the webapp satisfies our (dumb) assumption that
            // the view list is not empty
            session.setAttribute(Constants.QUERY, query.clone());
            session.removeAttribute(Constants.TEMPLATE_BUILD_STATE);
        }

        // TODO unclear why we need to put this path on the session
        Path path = query.getView().iterator().next();
        session.setAttribute("path", path.getStartClassDescriptor().getUnqualifiedName());

        // it's possible to not have a sort order
        if (query.getSortOrder() != null && !query.getSortOrder().isEmpty()) {
            Path sortPath = query.getSortOrder().keySet().iterator().next();
            String sortOrderString = sortPath.toStringNoConstraints();
            if (sortOrderString.indexOf(".") != -1) {
                sortOrderString  = sortOrderString.substring(0, sortOrderString.indexOf("."));
            }
            session.setAttribute("sortOrder", sortOrderString);
        }
        session.removeAttribute("prefix");

    }



    /**
     * Get the view list that the user is currently editing.
     * @param session current session
     * @return view list
     */
    public static List<Path> getEditingView(HttpSession session) {
        PathQuery query = (PathQuery) session.getAttribute(Constants.QUERY);
        if (query == null) {
            throw new IllegalStateException("No query on session");
        }
        return query.getView();
    }

    /**
     * Get the sort order for the query the user is currently editing
     * @param session current session
     * @return sort by list
     */
    public static Map<Path, String> getEditingSortOrder(HttpSession session) {
        PathQuery query = (PathQuery) session.getAttribute(Constants.QUERY);
        if (query == null) {
            throw new IllegalStateException("No query on session");
        }
        return query.getSortOrder();
    }

    /**
     * Save a clone of a query to the user's Profile.
     *
     * @param session The HTTP session
     * @param queryName the name to save the query under
     * @param query the PathQuery
     * @param created creation date
     * @return the SavedQuery created
     */
    public static SavedQuery saveQuery(HttpSession session,
                                 String queryName,
                                 PathQuery query,
                                 Date created) {
        Profile profile = (Profile) session.getAttribute(Constants.PROFILE);
        SavedQuery sq = new SavedQuery(queryName, (created != null ? created : new Date()),
                                       query.clone());
        profile.saveQuery(sq.getName(), sq);
        return sq;
    }

    /**
     * Save a clone of a query to the user's Profile.
     *
     * @param session The HTTP session
     * @param queryName the name to save the query under
     * @param query the PathQuery
     */
    public static void saveQuery(HttpSession session,
                                 String queryName,
                                 PathQuery query) {
        saveQuery(session, queryName, query, null);
    }

    /**
     * Save a clone of a query to the user's Profile history.
     *
     * @param session The HTTP session
     * @param queryName the name to save the query under
     * @param query the PathQuery
     */
    public static void saveQueryToHistory(HttpSession session,
                                          String queryName,
                                          PathQuery query) {
        Profile profile = (Profile) session.getAttribute(Constants.PROFILE);
        SavedQuery sq = new SavedQuery(queryName, new Date(), query.clone());
        profile.saveHistory(sq);
    }

    /**
     * Record a message that will be stored in the session until it is displayed to
     * the user. This allows actions that result in a redirect to display
     * messages to the user after the redirect. Messages are stored in a Collection
     * session attribute so you may call this method multiple times to display
     * multiple messages.<p>
     *
     * <code>recordMessage</code> and <code>recordError</code> are used by
     * <code>InterMineRequestProcessor.processForwardConfig</code> to store errors
     * and messages in the session when a redirecting forward is about to occur.
     *
     * @param session The Session object in which to store the message
     * @param message The message to store
     * {@inheritDoc}
     */
    public static void recordMessage(String message, HttpSession session) {
        recordMessage(message, Constants.MESSAGES, session);
    }

    /**
     * Record an error.
     * @see SessionMethods#recordMessage(String, HttpSession)
     * @param error The error to store
     * @param session The Session object in which to store the message
     */
    public static void recordError(String error, HttpSession session) {
        recordMessage(error, Constants.ERRORS, session);
    }

    /**
     * Record a message that will be stored in the session until it is displayed to
     * the user. This allows actions that result in a redirect to display
     * message to the user after the redirect. Messages are stored in a Set
     * session attribute so you may call this method multiple times to display
     * multiple errors. Identical errors will be ignored.<p>
     *
     * The <code>attrib</code> parameter specifies the name of the session attribute
     * used to store the set of messages.
     *
     * @param session The Session object in which to store the message
     * @param attrib The name of the session attribute in which to store message
     * @param message The message to store
     */
    private static void recordMessage(String message, String attrib, HttpSession session) {
        Set<String> set = (Set<String>) session.getAttribute(attrib);
        if (set == null) {
            set = Collections.synchronizedSet(new LinkedHashSet<String>());
            session.setAttribute(attrib, set);
        }
        set.add(message);
    }

    /**
     * Log use of a template query.
     *
     * @param session The session of the user running the template query
     * @param templateType The type of template 'global' or 'user'
     * @param templateName The name of the template
     */
    public static void logTemplateQueryUse(HttpSession session, String templateType,
            String templateName) {
        Logger log = Logger.getLogger(TemplateAction.class);
        Profile profile = (Profile) session.getAttribute(Constants.PROFILE);
        String username = profile.getUsername();

        if (username == null) {
            username = "anonymous";
        }

        log.info(username + "\t" + templateType + "\t" + templateName);
    }

    /**
     * Log load of an example query. If user is not logged in then lo
     *
     * @param session The session of the user loading the example query
     * @param exampleName The name of the example query loaded
     */
    public static void logExampleQueryUse(HttpSession session, String exampleName) {
        Logger log = Logger.getLogger(LoadQueryAction.class);
        Profile profile = (Profile) session.getAttribute(Constants.PROFILE);
        String username = profile.getUsername();

        if (username == null) {
            username = "anonymous";
        }

        log.info(username + "\t" + exampleName);
    }

    /**
     * Get the COLLAPSED map from the session. If the attribute is not present then a new
     * map will be created.
     *
     * @param session the curren session
     * @return the COLLAPSED map attribute
     */
    public static Map getCollapsedMap(HttpSession session) {
        Map collapsed = (Map) session.getAttribute(Constants.COLLAPSED);
        if (collapsed == null) {
            collapsed = new HashMap();
            session.setAttribute(Constants.COLLAPSED, collapsed);
        }
        return collapsed;
    }

    /**
     * Return the displayObjects Map from the session or create and return it if it doesn't exist.
     *
     * @param session the HttpSession to get the displayObjects Map from
     * @return the (possibly new) displayObjects Map
     */
    public static DisplayObjectFactory getDisplayObjects(HttpSession session) {
        DisplayObjectFactory displayObjects =
            (DisplayObjectFactory) session.getAttribute(Constants.DISPLAY_OBJECT_CACHE);

        // Build map from object id to DisplayObject
        if (displayObjects == null) {
            displayObjects = new DisplayObjectFactory(session);
            session.setAttribute(Constants.DISPLAY_OBJECT_CACHE, displayObjects);
        }

        return displayObjects;
    }

    /**
     * Initialise a new session. Adds a profile to the session.
     *
     * @param session the new session to initialise
     */
    public static void initSession(HttpSession session) {
        InterMineAPI im = getInterMineAPI(session);
        ProfileManager pm = im.getProfileManager();
        session.setAttribute(Constants.PROFILE, new Profile(pm, null, null, null,
                    new HashMap(), new HashMap(), new HashMap()));
        session.setAttribute(Constants.COLLAPSED, new HashMap());
        session.setAttribute(Constants.DISPLAY_OBJECT_CACHE, new DisplayObjectFactory(session));
        session.setAttribute(Constants.RESULTS_TABLE_SIZE, Constants.DEFAULT_TABLE_SIZE);
    }

    /**
     * Start the current query running in the background, then return.  A new query id will be
     * created and added to the RUNNING_QUERIES session attriute.  That attribute is a Map from
     * query id to QueryMonitor.  A Thread will be created to update the QueryMonitor.
     * @param monitor the monitor for this query - controls cancelling and receives feedback
     *                about how the query concluded
     * @param session the current http session
     * @param messages messages resources (for messages and errors)
     * @param saveQuery whether or not to automatically save the query
     * @param pathQuery query to start
     * @return the new query id created
     */
    public static String startQuery(final QueryMonitor monitor,
                                    final HttpSession session,
                                    final MessageResources messages,
                                    final boolean saveQuery,
                                    final PathQuery pathQuery) {
        synchronized (session) {

            Map queries = getRunningQueries(session);
            final String qid = "" + topQueryId++;
            queries.put(qid, monitor);

            new Thread(new Runnable() {
                public void run () {
                    final Profile profile = (Profile) session.getAttribute(Constants.PROFILE);
                    try {
                        final InterMineAPI im = getInterMineAPI(session);
                        WebResultsExecutor executor = im.getWebResultsExecutor(profile);
                        final PagedTable pr = new PagedTable((executor.execute(pathQuery)));
                        Action action = new Action() {
                            public void process() {
                                pr.getRows();
                            }
                        };
                        CompletionCallBack completionCallBack = new CompletionCallBack() {
                            public void complete() {
                                SessionMethods.setResultsTable(session, "results." + qid, pr);
                            }
                        };
                        SessionMethods.runQuery(session, messages, qid, action, completionCallBack);

                        if (saveQuery) {
                            String queryName = NameUtil.findNewQueryName(
                                    profile.getHistory().keySet());
                            pathQuery.setInfo(pr.getWebTable().getInfo());
                            saveQueryToHistory(session, queryName, pathQuery);
                            recordMessage(messages.getMessage("saveQuery.message", queryName),
                                          session);
                        }

                        // pause because we don't want to remove the monitor from the
                        // session until client has retrieved it in order to work out
                        // where to go next
                        Thread.sleep(20000);
                    } catch (Exception err) {
                        StringWriter sw = new StringWriter();
                        err.printStackTrace(new PrintWriter(sw));
                        LOG.error(sw.toString());
                        StringBuffer errorMessage = new StringBuffer("Error while running query");
                        if (session.getAttribute(Constants.IS_SUPERUSER) != null
                            && (Boolean) session.getAttribute(Constants.IS_SUPERUSER)) {
                            errorMessage.append(": " + err.getMessage());
                        }
                        recordError(errorMessage.toString(), session);
                    } finally {
                        LOG.debug("unregisterRunningQuery qid " + qid);
                        ((Map) session.getAttribute("RUNNING_QUERIES")).remove(qid);
                    }
                }
            }).start();

            return qid;
        }
    }

    /**
     * Start a query running in the background that will return the row count of the collection.
     * A new query id will be created and added to the RUNNING_QUERIES session attriute.
     * That attribute is a Map from query id to QueryMonitor.  A Thread will be created to
     * update the QueryMonitor.
     * @param monitor the monitor for this query - controls cancelling and receives feedback
     *                about how the query concluded
     * @param session the current http session
     * @param messages messages resources (for messages and errors)
     * @return the new query id
     */
    public static String startPagedTableCount(final PageTableQueryMonitor monitor,
                                              final HttpSession session,
                                              final MessageResources messages) {
        synchronized (session) {
            Map queries = getRunningQueries(session);
            final String qid = "" + topQueryId++;
            queries.put(qid, monitor);

            new Thread(new Runnable() {
                public void run () {
                    try {
                        LOG.debug("startQuery qid " + qid + " thread started");

                        class CountAction implements Action
                        {
                            public void process() {
                                monitor.getPagedTable().getExactSize();
                            }
                        }

                        Action action = new CountAction();

                        SessionMethods.runQuery(session, messages, qid, action, null);

                        // pause because we don't want to remove the monitor from the
                        // session until client has retrieved it in order to work out
                        // where to go next
                        Thread.sleep(20000);
                    } catch (Exception err) {
                        StringWriter sw = new StringWriter();
                        err.printStackTrace(new PrintWriter(sw));
                        recordError(sw.toString(), session);
                        LOG.error(sw.toString());
                    } finally {
                        LOG.debug("unregisterRunningQuery qid " + qid);
                        ((Map) session.getAttribute("RUNNING_QUERIES")).remove(qid);
                    }
                }
            }).start();

            return qid;
        }

    }

    /**
     * Return the Map of currently running queries from the session.
     */
    private static Map<String, QueryMonitor> getRunningQueries(final HttpSession session) {
        Map queries = (Map) session.getAttribute(Constants.RUNNING_QUERIES);
        if (queries == null) {
            queries = new HashMap();
            session.setAttribute(Constants.RUNNING_QUERIES, queries);
        }
        return queries;
    }


    /**
     * Start a query running in the background that will return the row count of the query argument.
     * A new query id will be created and added to the RUNNING_QUERIES session attriute.
     * That attribute is a Map from query id to QueryMonitor.  A Thread will be created to
     * update the QueryMonitor.
     * @param monitor the monitor for this query - controls cancelling and receives feedback
     *                about how the query concluded
     * @param session the current http session
     * @param messages messages resources (for messages and errors)
     * @return the new query id created
     */
    public static String startQueryCount(final QueryCountQueryMonitor monitor,
                                         final HttpSession session,
                                         final MessageResources messages) {
        synchronized (session) {
            Map queries = getRunningQueries(session);
            final String qid = "" + topQueryId++;
            queries.put(qid, monitor);

            final Query query = monitor.getQuery();
            final InterMineAPI im = getInterMineAPI(session);
            final ObjectStore os = im.getObjectStore();

            new Thread(new Runnable() {
                public void run () {
                    try {
                        LOG.debug("startQuery qid " + qid + " thread started");
                        Action action = new Action() {
                            public void process() {
                                try {
                                    monitor.setCount(os.count(query, ObjectStore.SEQUENCE_IGNORE));
                                } catch (ObjectStoreException e) {
                                    throw new RuntimeException("failed to get count of: " + query,
                                                               e);
                                }
                            }
                        };
                        SessionMethods.runQuery(session, messages, qid, action, null);

                        // pause because we don't want to remove the monitor from the
                        // session until client has retrieved it in order to work out
                        // where to go next
                        Thread.sleep(20000);
                    } catch (Exception err) {
                        StringWriter sw = new StringWriter();
                        err.printStackTrace(new PrintWriter(sw));
                        recordError(sw.toString(), session);
                        LOG.error(sw.toString());
                    } finally {
                        LOG.debug("unregisterRunningQuery qid " + qid);
                        ((Map) session.getAttribute("RUNNING_QUERIES")).remove(qid);
                    }
                }
            }).start();

            return qid;
        }
    }

    /**
     * Get the QueryMonitor object corresponding to a running query id (qid).
     *
     * @param qid the query id returned by startQuery
     * @param session the users session
     * @return QueryMonitor registered to the query id
     */
    public static QueryMonitor getRunningQueryController(String qid, HttpSession session) {
        synchronized (session) {
            Map queries = (Map) session.getAttribute("RUNNING_QUERIES");
            QueryMonitor controller = (QueryMonitor) queries.get(qid);
            return controller;
        }
    }

    /**
     * Given a table identifier, return the cached PagedTable.
     *
     * @param session the current session
     * @param identifier table identifier
     * @return PagedTable identified by identifier
     */
    public static PagedTable getResultsTable(HttpSession session, String identifier) {
        Map tables = (Map) session.getAttribute(Constants.TABLE_MAP);
        if (tables != null) {
            return (PagedTable) tables.get(identifier);
        }
        return null;
    }

    /**
     *
     *
     * @param session the current session
     * @param identifier table identifier
     * @param table table to register
     */
    public static void setResultsTable(HttpSession session, String identifier, PagedTable table) {
        Map tables = (Map) session.getAttribute(Constants.TABLE_MAP);
        if (tables == null) {
            tables = Collections.synchronizedMap(new LRUMap(100));
            session.setAttribute(Constants.TABLE_MAP, tables);
        }
        tables.put(identifier, table);
        table.setTableid(identifier);
    }

    /**
     * Remove a cached bag table from the session.
     * @param session the current session
     * @param name the bag name
     */
    public static void invalidateBagTable(HttpSession session, String name) {
        Map tables = (Map) session.getAttribute(Constants.TABLE_MAP);
        if (tables != null) {
            tables.remove("bag." + name);
        }
    }

    /**
     * Get the default operator to apply to new constraints.
     * @param session the session
     * @return operator name ("and" or "or")
     */
    public static String getDefaultOperator(HttpSession session) {
        String op = (String) session.getAttribute(Constants.DEFAULT_OPERATOR);
        if (op == null) {
            op = "and";
        }
        return op;
    }


    /**
     * Return true if and only if the current user if the superuser.
     * @param session the session
     * @return true for superuser
     */
    public static boolean isSuperUser(HttpSession session) {
        Boolean superUserAttribute = (Boolean) session.getAttribute(Constants.IS_SUPERUSER);
        return superUserAttribute != null && superUserAttribute.equals(Boolean.TRUE);
    }

    /**
     * Move an attribute from the session to the request, removing it from the session.
     * @param attributeName the attribute name
     * @param request the current request
     */
    public static void moveToRequest(String attributeName, HttpServletRequest request) {
        HttpSession session = request.getSession();
        request.setAttribute(attributeName, session.getAttribute(attributeName));
        session.removeAttribute(attributeName);
    }

    /**
     * Execute a query and return a PagedTable to display contents of an InterMineBag
     *
     * @param request the request
     * @param imBag the InterMineBag
     * @return a PagedTable
     * @throws ObjectStoreException thrown exception
     */
    public static PagedTable doQueryGetPagedTable(HttpServletRequest request, InterMineBag imBag)
        throws ObjectStoreException {
        HttpSession session = request.getSession();
        final InterMineAPI im = getInterMineAPI(session);
        Model model = im.getModel();
        WebConfig webConfig = SessionMethods.getWebConfig(request);

        PathQuery pathQuery = PathQueryResultHelper.makePathQueryForBag(imBag, webConfig, model);
        WebResultsExecutor executor = im.getWebResultsExecutor(getProfile(session));
        WebResults webResults = executor.execute(pathQuery);

        String identifier = "bag." + imBag.getName();
        PagedTable pagedResults = new PagedTable(webResults);
        setResultsTable(session, identifier, pagedResults);
        return pagedResults;
    }

    /**
     * Execute a query and return a PagedTable to display contents of a Collection, field of
     * a given InterMineObject
     *
     * @param request the ServletRequest
     * @param obj the InterMineObject
     * @param field the name of the collection field in the InterMineObject
     * @param referencedClassName the type of the collection
     * @return a PagedTable
     * @throws ObjectStoreException exception thrown
     */
    public static PagedTable doQueryGetPagedTable(HttpServletRequest request, InterMineObject obj,
                                                  String field,
            String referencedClassName) throws ObjectStoreException {
        HttpSession session = request.getSession();
        final InterMineAPI im = getInterMineAPI(session);
        ObjectStore os = im.getObjectStore();
        WebConfig webConfig = getWebConfig(request);
        PathQuery pathQuery = PathQueryResultHelper.makePathQueryForCollection(webConfig, os, obj,
                        referencedClassName, field);
        session.setAttribute(Constants.QUERY, pathQuery);

        WebResultsExecutor executor = im.getWebResultsExecutor(getProfile(session));
        WebResults webResults = executor.execute(pathQuery);

        String identifier = "coll" + index++;
        PagedTable pagedResults = new PagedTable(webResults);
        setResultsTable(session, identifier, pagedResults);
        return pagedResults;
    }


    /**
     * @param request a HttpServletRequest
     * @return WebConfig
     */
    public static WebConfig getWebConfig(HttpServletRequest request) {
        return getWebConfig(request.getSession().getServletContext());
    }

    /**
     * @param context a ServletContext
     * @return WebConfig
     */
    public static WebConfig getWebConfig(ServletContext context) {
        WebConfig wc = (WebConfig) context.getAttribute(Constants.WEBCONFIG);
        if (wc == null) {
            throw new RuntimeException("WebConfig not present in web session.");
        }
        return wc;
    }

    /**
     * Sets the WebConfig into the ServletContext.
     *
     * @param context a ServletContext
     * @param webConfig a WebConfig object
     */
    public static void setWebConfig(ServletContext context, WebConfig webConfig) {
        context.setAttribute(Constants.WEBCONFIG, webConfig);
    }

    /**
     * Get WebState object that is used for saving state of webapp GUI.
     * @param session session
     * @return WebState
     */
    public static WebState getWebState(HttpSession session) {
        WebState webState = (WebState) session.getAttribute(Constants.WEB_STATE);
        if (webState == null) {
            webState = new WebState();
            session.setAttribute(Constants.WEB_STATE, webState);
        }
        return webState;
    }

    /**
     * Returns user profile saved in session.
     * @param session session
     * @return user profile
     */
    public static Profile getProfile(HttpSession session) {
        return (Profile) session.getAttribute(Constants.PROFILE);
    }

    /**
     * Sets the user profile in the session.
     *
     * @param session the session
     * @param profile a Profile object to put in the session
     */
    public static void setProfile(HttpSession session, Profile profile) {
        session.setAttribute(Constants.PROFILE, profile);
    }

    /**
     * Returns the Map of aspects.
     *
     * @param servletContext a ServletContext object
     * @return a Map
     */
    public static Map<String, Aspect> getAspects(ServletContext servletContext) {
        return (Map<String, Aspect>) servletContext.getAttribute(Constants.ASPECTS);
    }

    /**
     * Sets the Map of aspects.
     *
     * @param servletContext a ServletContext object
     * @param aspects a Map
     */
    public static void setAspects(ServletContext servletContext, Map<String, Aspect> aspects) {
        servletContext.setAttribute(Constants.ASPECTS, aspects);
    }

    /**
     * Returns the web properties.
     *
     * @param servletContext a ServletContext object
     * @return a Properties object
     */
    public static Properties getWebProperties(ServletContext servletContext) {
        return (Properties) servletContext.getAttribute(Constants.WEB_PROPERTIES);
    }

    /**
     * Sets the web properties in the session.
     *
     * @param servletContext a ServletContext object
     * @param props a Properties object
     */
    public static void setWebProperties(ServletContext servletContext, Properties props) {
        servletContext.setAttribute(Constants.WEB_PROPERTIES, props);
    }

    /**
     * Returns the PathQuery on the session.
     *
     * @param session a HttpSession object
     * @return a PathQuery for the current user from the session
     */
    public static PathQuery getQuery(HttpSession session) {
        return (PathQuery) session.getAttribute(Constants.QUERY);
    }

    /**
     * Set the current query on the session.
     *
     * @param session a HttpSession object
     * @param query a PathQuery object
     */
    public static void setQuery(HttpSession session, PathQuery query) {
        session.setAttribute(Constants.QUERY, query);
    }

    /**
     * Removes the current query from the session.
     *
     * @param session a HttpSession object
     */
    public static void removeQuery(HttpSession session) {
        session.removeAttribute(Constants.QUERY);
    }

    /**
     * Get the SearchRepository for global (public) objects.
     *
     * @param context the servlet context
     * @return the singleton SearchRepository object
     */
    public static SearchRepository getGlobalSearchRepository(ServletContext context) {
        return (SearchRepository) context.getAttribute(Constants.GLOBAL_SEARCH_REPOSITORY);
    }

    /**
     * Set the SearchRepository for global (public) objects in the servlet context.
     *
     * @param context the servlet context
     * @param repo the SearchRepository object
     */
    public static void setGlobalSearchRepository(ServletContext context, SearchRepository repo) {
        context.setAttribute(Constants.GLOBAL_SEARCH_REPOSITORY, repo);
    }

    /**
     * Get the InterMineAPI which provides access to core features of an InterMine application.
     * @param session the webapp session
     * @return the InterMine core object
     */
    public static InterMineAPI getInterMineAPI(HttpSession session) {
        return getInterMineAPI(session.getServletContext());
    }

    /**
     * Get the InterMineAPI which provides access to core features of an InterMine application.
     * @param servletContext the webapp servletContext
     * @return the InterMine core object
     */
    public static InterMineAPI getInterMineAPI(ServletContext servletContext) {
        return (InterMineAPI) servletContext.getAttribute(Constants.INTERMINE_API);
    }

    /**
     * Sets the InterMineAPI in the servlet context.
     *
     * @param servletContext the webapp servlet context
     * @param im the InterMineAPI object
     */
    public static void setInterMineAPI(ServletContext servletContext, InterMineAPI im) {
        servletContext.setAttribute(Constants.INTERMINE_API, im);
    }

    /**
     * Get the AutoCompleter stored on the ServletContext.
     *
     * @param servletContext a ServletContext object
     * @return the AutoCompleter
     */
    public static AutoCompleter getAutoCompleter(ServletContext servletContext) {
        return (AutoCompleter) servletContext.getAttribute(Constants.AUTO_COMPLETER);
    }

    /**
     * Set the AutoCompleter into the ServletContext.
     *
     * @param servletContext a ServletContext object
     * @param ac an AutoCompleter object
     */
    public static void setAutoCompleter(ServletContext servletContext, AutoCompleter ac) {
        servletContext.setAttribute(Constants.AUTO_COMPLETER, ac);
    }

    /**
     * Get the TemplateBuildState from the session.
     *
     * @param session the session
     * @return a TemplateBuildState object, or null if one is not on the session
     */
    public static TemplateBuildState getTemplateBuildState(HttpSession session) {
        return (TemplateBuildState) session.getAttribute(Constants.TEMPLATE_BUILD_STATE);
    }

    /**
     * Remove the TemplateBuildState from the session.
     *
     * @param session the session
     */
    public static void removeTemplateBuildState(HttpSession session) {
        session.removeAttribute(Constants.TEMPLATE_BUILD_STATE);
    }

    /**
     * Sets the TemplateBuildState on the session.
     *
     * @param session the session
     * @param tbs a TemplateBuildState object to put on the session
     */
    public static void setTemplateBuildState(HttpSession session, TemplateBuildState tbs) {
        session.setAttribute(Constants.TEMPLATE_BUILD_STATE, tbs);
    }

    /**
     * Gets the aspect categories from the servlet context.
     *
     * @param servletContext the ServletContext
     * @return a Set of aspect names
     */
    public static Set<String> getCategories(ServletContext servletContext) {
        return (Set<String>) servletContext.getAttribute(Constants.CATEGORIES);
    }

    /**
     * Sets the aspect categories into the servlet context.
     *
     * @param servletContext the ServletContext
     * @param categories the Set of aspect names
     */
    public static void setCategories(ServletContext servletContext, Set<String> categories) {
        servletContext.setAttribute(Constants.CATEGORIES, categories);
    }
}
