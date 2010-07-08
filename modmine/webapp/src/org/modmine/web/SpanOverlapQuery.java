package org.modmine.web;

/*
 * Copyright (C) 2002-2010 FlyMine
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

//import org.apache.log4j.Logger;
import org.intermine.api.InterMineAPI;
import org.intermine.model.bio.Chromosome;
import org.intermine.model.bio.LocatedSequenceFeature;
import org.intermine.model.bio.Location;
import org.intermine.model.bio.Organism;
import org.intermine.model.bio.Submission;
import org.intermine.objectstore.query.BagConstraint;
import org.intermine.objectstore.query.ConstraintOp;
import org.intermine.objectstore.query.ConstraintSet;
import org.intermine.objectstore.query.ContainsConstraint;
import org.intermine.objectstore.query.OverlapConstraint;
import org.intermine.objectstore.query.OverlapRange;
import org.intermine.objectstore.query.Query;
import org.intermine.objectstore.query.QueryClass;
import org.intermine.objectstore.query.QueryCollectionReference;
import org.intermine.objectstore.query.QueryField;
import org.intermine.objectstore.query.QueryObjectReference;
import org.intermine.objectstore.query.QueryValue;
import org.intermine.objectstore.query.Results;
import org.intermine.objectstore.query.ResultsRow;
import org.intermine.objectstore.query.SimpleConstraint;

/**
 * SpanOverlapQuery is a query logic class. It has two methods. runSpanValidationQuery queries the
 * information of all the organisms and their chromosomes' names and length for span validation use.
 * runSpanOverlapQuery queries LocatedSequenceFeatures that overlap users' spans.
 *
 * @author Fengyuan Hu
 *
 */
public class SpanOverlapQuery
{

//    private static final Logger LOG = Logger.getLogger(SpanOverlapQuery.class);

    /**
     * Query the information of all the organisms and their chromosomes' names and length. The
     * results is stored in a Map. The result data will be used to validate users' span data.
     * For each span, its chromosome must match the chrPID and range must not go beyond the length.
     *
     * @param im - the InterMineAPI
     * @return chrInfoMap - a HashMap with rgName as key and its chrInfo accordingly as value
     */
    public static Map<String, List<ChromosomeInfo>> runSpanValidationQuery(InterMineAPI im) {

        // a Map contains orgName and its chrInfo accordingly
        // e.g. <D.Melanogaster, (D.Melanogaster, X, 5000)...>
        Map<String, List<ChromosomeInfo>> chrInfoMap = new HashMap<String, List<ChromosomeInfo>>();

        try {
            Query q = new Query();

            QueryClass qcOrg = new QueryClass(Organism.class);
            QueryClass qcChr = new QueryClass(Chromosome.class);

            // Result columns
            QueryField qfOrgName = new QueryField(qcOrg, "name");
            QueryField qfChrPID = new QueryField(qcChr, "primaryIdentifier");
            QueryField qfChrLength = new QueryField(qcChr, "length");

            // As in SQL SELECT ?,?,?
            q.addToSelect(qfOrgName);
            q.addToSelect(qfChrPID);
            q.addToSelect(qfChrLength);

            // As in SQL FROM ?,?
            q.addFrom(qcChr);
            q.addFrom(qcOrg);

            // As in SQL WHERE ?
            QueryObjectReference organism = new QueryObjectReference(qcChr,
                    "organism");
            ContainsConstraint ccOrg = new ContainsConstraint(organism,
                    ConstraintOp.CONTAINS, qcOrg);
            q.setConstraint(ccOrg);

            Results results = im.getObjectStore().execute(q);

            // a List contains all the chrInfo query back from db
            List<ChromosomeInfo> chrInfoList = new ArrayList<ChromosomeInfo>();
            // a Set contains all the orgName
            Set<String> orgSet = new HashSet<String>();

            // Handle results
            for (Iterator<?> iter = results.iterator(); iter.hasNext();) {
                ResultsRow<?> row = (ResultsRow<?>) iter.next();

                String orgName = (String) row.get(0);
                String chrPID = (String) row.get(1);
                Integer chrLength = (Integer) row.get(2);

                // add orgName to HashSet to filter out duplication
                orgSet.add(orgName);

                if (chrLength != null) {
                    ChromosomeInfo chrInfo = new ChromosomeInfo();
                    chrInfo.setOrgName(orgName);
                    chrInfo.setChrPID(chrPID);
                    chrInfo.setChrLength(chrLength);
                    // add ChromosomeInfo to Arraylist
                    chrInfoList.add(chrInfo);
                }
            }

            // iterate orgSet and chrInfoList to put data in chrInfoMap which has the key as the
            // orgName and value as a ArrayList containing a list of chrInfo which has the same
            // orgName
            for (String orgName : orgSet) {

                // a List to store chrInfo for the same organism
                List<ChromosomeInfo> chrInfoSubList = new ArrayList<ChromosomeInfo>();

                for (ChromosomeInfo chrInfo : chrInfoList) {
                    if (orgName.equals(chrInfo.getOrgName())) {
                        chrInfoSubList.add(chrInfo);
                        chrInfoMap.put(orgName, chrInfoSubList);
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return chrInfoMap;

    }

    /**
     * @param form A SpanUploadForm object
     * @param spanList Spans in an ArrayList
     * @param im A InterMineAPI object
     * @return spanOverlapResultMap A HashMap contains all the spans and their query results
     */
    public static Map<Span, Results> runSpanOverlapQuery(SpanUploadForm form,
            List<Span> spanList, InterMineAPI im) {

        String orgName = form.getOrgName();
        String[] featureTypes = form.getFeatureTypes();
        Integer[] submissions = form.getSubmissions();

        //>>>>> TEST CODE <<<<<
//        String[] featureTypes = new String[4];
//        featureTypes[3] = "gene";
//        featureTypes[2] = "transcript_region";
//        featureTypes[1] = "protein_binding_site";
//        featureTypes[0] = "binding_site";
//
//        String[] submissions = new String[3];
//        submissions[0] = "Orc2 S2 ChIP-Seq";
//        submissions[1] = "pre-RC complexes MCM_cycE Kc";
//        submissions[2] = "Orc2 BG3 ChIP-Seq";
        //>>>>> TEST CODE <<<<<

        List<String> ftKeys = Arrays.asList(featureTypes);
        List<Integer> subKeys = Arrays.asList(submissions);

        //>>>>> TEST CODE <<<<<
//        LOG.info("featureTypes: " + ftKeys.toString());
//        LOG.info("submissions: " + subKeys.toString());
        //>>>>> TEST CODE <<<<<

        Map<Span, Results> spanOverlapResultMap = new HashMap<Span, Results>();

        try {
            Query q = new Query();

            for (Span aSpan: spanList) {

                String chrPID = aSpan.getChr();
                Integer start = aSpan.getStart();
                Integer end = aSpan.getEnd();

                //>>>>> TEST CODE <<<<<
//                LOG.info("orgName: " + orgName);
//                LOG.info("chrPID: " + chrPID);
//                LOG.info("start: " + start);
//                LOG.info("end: " + end);
                //>>>>> TEST CODE <<<<<

                // DB tables
                QueryClass qcOrg = new QueryClass(Organism.class);
                QueryClass qcChr = new QueryClass(Chromosome.class);
                QueryClass qcFeature = new QueryClass(LocatedSequenceFeature.class);
                QueryClass qcLoc = new QueryClass(Location.class);
                QueryClass qcSubmission = new QueryClass(Submission.class);

                QueryField qfOrgName = new QueryField(qcOrg, "name");
                QueryField qfChrPID = new QueryField(qcChr, "primaryIdentifier");
                QueryField qfFeaturePID = new QueryField(qcFeature, "primaryIdentifier");
                QueryField qfFeatureType = new QueryField(qcFeature, "featureType");
                QueryField qfSubmissionTitle = new QueryField(qcSubmission, "title");
                QueryField qfSubmissionDCCid = new QueryField(qcSubmission, "DCCid");
                QueryField qfChr = new QueryField(qcChr, "primaryIdentifier");
                QueryField qfLocStart = new QueryField(qcLoc, "start");
                QueryField qfLocEnd = new QueryField(qcLoc, "end");

                q.setDistinct(true);

                q.addToSelect(qfFeaturePID);
                q.addToSelect(qfFeatureType);
                q.addToSelect(qfChr);
                q.addToSelect(qfLocStart);
                q.addToSelect(qfLocEnd);
                q.addToSelect(qfSubmissionDCCid);

                q.addFrom(qcChr);
                q.addFrom(qcOrg);
                q.addFrom(qcFeature);
                q.addFrom(qcLoc);
                q.addFrom(qcSubmission);

                ConstraintSet constraints = new ConstraintSet(ConstraintOp.AND);

                q.setConstraint(constraints);

                // LocatedSequenceFeature.organism = Organism
                QueryObjectReference organism = new QueryObjectReference(qcFeature,
                        "organism");
                ContainsConstraint ccOrg = new ContainsConstraint(organism,
                        ConstraintOp.CONTAINS, qcOrg);
                constraints.addConstraint(ccOrg);

                // Organism.name = orgName
                SimpleConstraint scOrg = new SimpleConstraint(qfOrgName,
                        ConstraintOp.EQUALS, new QueryValue(orgName));
                constraints.addConstraint(scOrg);

                // Location.subject = LocatedSequenceFeature
                QueryObjectReference locSubject = new QueryObjectReference(qcLoc,
                        "subject");
                ContainsConstraint ccLocSubject = new ContainsConstraint(locSubject,
                        ConstraintOp.CONTAINS, qcFeature);
                constraints.addConstraint(ccLocSubject);

                // Location.object = Chromosome
                QueryObjectReference locObject = new QueryObjectReference(qcLoc,
                        "object");
                ContainsConstraint ccLocObject = new ContainsConstraint(locObject,
                        ConstraintOp.CONTAINS, qcChr);
                constraints.addConstraint(ccLocObject);

                // Chromosome.primaryIdentifier = chrPID
                SimpleConstraint scChr = new SimpleConstraint(qfChrPID,
                        ConstraintOp.EQUALS, new QueryValue(chrPID));
                constraints.addConstraint(scChr);

                // LocatedSequenceFeature.submissions = Submission
                QueryCollectionReference submission = new QueryCollectionReference(qcFeature,
                         "submissions");
                ContainsConstraint ccSubmission = new ContainsConstraint(submission,
                        ConstraintOp.CONTAINS, qcSubmission);
                constraints.addConstraint(ccSubmission);

                // LocatedSequenceFeature.featureType in a list
                constraints.addConstraint(new BagConstraint(qfFeatureType,
                        ConstraintOp.IN, ftKeys));
                // Submission.title in a list
                constraints.addConstraint(new BagConstraint(qfSubmissionTitle,
                        ConstraintOp.IN, subKeys));


                OverlapRange overlapInput = new OverlapRange(new QueryValue(start),
                        new QueryValue(end), locObject);
                OverlapRange overlapFeature = new OverlapRange(new QueryField(qcLoc,
                        "start"), new QueryField(qcLoc, "end"), locObject);
                OverlapConstraint oc = new OverlapConstraint(overlapInput,
                        ConstraintOp.OVERLAPS, overlapFeature);
                constraints.addConstraint(oc);

                Results results = im.getObjectStore().execute(q);

                //>>>>> TEST CODE <<<<<
//                LOG.info("Result Size: " + results.size());
//                LOG.info("Result: " + results);
                //>>>>> TEST CODE <<<<<

                spanOverlapResultMap.put(aSpan, results);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }


        return spanOverlapResultMap;
    }
}