package org.intermine.bio.dataconversion;

/*
 * Copyright (C) 2002-2012 FlyMine
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;

import org.apache.commons.collections.keyvalue.MultiKey;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.apache.tools.ant.BuildException;
import org.intermine.dataconversion.ItemWriter;
import org.intermine.metadata.Model;
import org.intermine.objectstore.ObjectStoreException;
import org.intermine.util.FormattedTextParser;
import org.intermine.util.StringUtil;
import org.intermine.xml.full.Item;

/**
 * HomoloGene data converter, to use symbol and organism to identify a gene
 *
 * @author Fengyuan Hu
 */
public class HomologeneConverter extends BioFileConverter
{
    private static final Logger LOG = Logger.getLogger(HomologeneConverter.class);
    private IdResolver rslv;
    private static final String DATASET_TITLE = "HomoloGene data set";
    private static final String DATA_SOURCE_NAME = "HomoloGene";

    private static final String PROP_FILE = "homologene_config.properties";
    private static final String DEFAULT_IDENTIFIER_FIELD = "primaryIdentifier";

    private Set<String> taxonIds = new HashSet<String>();
    private Set<String> homologues = new HashSet<String>();

    private static final String ORTHOLOGUE = "orthologue";
    private static final String PARALOGUE = "paralogue";

    private static final String EVIDENCE_CODE_ABBR = "AA";
    private static final String EVIDENCE_CODE_NAME = "Amino acid sequence comparison";

    private Properties props = new Properties();
    private Map<String, String> config = new HashMap<String, String>();
    private static String evidenceRefId = null;

    private Map<MultiKey, String> identifiersToGenes = new HashMap<MultiKey, String>();

    /**
     * Constructor
     * @param writer the ItemWriter used to handle the resultant items
     * @param model the Model
     */
    public HomologeneConverter(ItemWriter writer, Model model) throws ObjectStoreException {
        super(writer, model, DATA_SOURCE_NAME, DATASET_TITLE);
        // I don't know what this is for
//        readConfig();
    }

    /**
     * Sets the list of taxonIds that should be processed.  All genes will be loaded.
     *
     * @param taxonIds a space-separated list of taxonIds
     */
    public void setHomologeneOrganisms(String taxonIds) {
        this.taxonIds = new HashSet<String>(Arrays.asList(StringUtil.split(taxonIds, " ")));
        LOG.info("Setting list of organisms to " + taxonIds);
    }

    /**
     * Sets the list of taxonIds of homologues that should be processed.  These homologues will only
     * be processed if they are homologues for the organisms of interest.
     *
     * @param homologues a space-separated list of taxonIds
     */
    public void setHomologeneHomologues(String homologues) {
        this.homologues = new HashSet<String>(Arrays.asList(StringUtil.split(homologues, " ")));
        LOG.info("Setting list of homologues to " + homologues);
    }

    /**
     * {@inheritDoc}
     */
    public void process(Reader reader) throws Exception {
        /*
            homologene.data is a tab delimited file containing the following
            columns:

            1) HID (HomoloGene group id) - uid, http://www.ncbi.nlm.nih.gov/homologene?term=3[uid]
            2) Taxonomy ID
            3) Gene ID - NBCI Id
            4) Gene Symbol
            5) Protein gi
            6) Protein accession
        */

        String currentGroup = null;
        String previousGroup = null;

        // flat structure of homologue info
        Set<GeneRecord> genes = new HashSet<GeneRecord>();

        if (taxonIds.isEmpty()) {
            throw new BuildException("homologene.organisms property not set in project XML file");
        }
        if (homologues.isEmpty()) {
            LOG.warn("homologene.homologues property not set in project XML file");
        }

        Set<String> allTaxonIds = new HashSet<String>();
        allTaxonIds.addAll(taxonIds);
        allTaxonIds.addAll(homologues);
        
        if (rslv == null) {
            rslv = IdResolverService.getIdResolverByOrganism(allTaxonIds);
        }
        
        Iterator<String[]> lineIter = FormattedTextParser.parseTabDelimitedReader(reader);
        while (lineIter.hasNext()) {
            String[] bits = lineIter.next();
            if (bits.length < 6) {
                continue;
            }

            String groupId = bits[0];
            currentGroup = groupId;

            // at a different groupId, process previous homologue group
            if (previousGroup != null && !currentGroup.equals(previousGroup)) {
                if (genes.size() >= 2) {
                    processHomologues(genes);
                }
                genes = new HashSet<GeneRecord>(); 
            }

            previousGroup = groupId;
            
            String taxonId = bits[1];            
            if (!isValid(taxonId)) {
                // not an organism of interest, skip
                continue;
            }

            String ncbiId = bits[2];
            String symbol = bits[3];
            String gene = getGene(ncbiId, symbol, taxonId);
                        
            if (gene == null) {
                // invalid gene
                continue;
            }
            genes.add(new GeneRecord(gene, taxonId));
        }
    }

    private void readConfig() {
        try {
            props.load(getClass().getClassLoader().getResourceAsStream(
                    PROP_FILE));
        } catch (IOException e) {
            throw new RuntimeException("Problem loading properties '"
                    + PROP_FILE + "'", e);
        }

        for (Map.Entry<Object, Object> entry : props.entrySet()) {
            String key = (String) entry.getKey(); // e.g. 10090.identifier
            String value = ((String) entry.getValue()).trim(); // e.g. symbol

            String[] attributes = key.split("\\.");
            if (attributes.length == 0) {
                throw new RuntimeException("Problem loading properties '"
                        + PROP_FILE + "' on line " + key);
            }
            String taxonId = attributes[0];
            config.put(taxonId, value);
        }
    }

    private void processHomologues(Set<GeneRecord> genes)
            throws ObjectStoreException {
        Set<GeneRecord> notProcessed = new HashSet<GeneRecord>(genes);
        for (GeneRecord gene : genes) {
            notProcessed.remove(gene);
            for (GeneRecord homologue : notProcessed) {
                createHomologue(gene.geneRefId, gene.taxonId, homologue.geneRefId, homologue.taxonId);
                createHomologue(homologue.geneRefId, homologue.taxonId, gene.geneRefId, gene.taxonId);
            }
        }
    }

    private void createHomologue(String gene1, String taxonId1, String gene2, String taxonId2) 
            throws ObjectStoreException {
        Item homologue = createItem("Homologue");
        homologue.setReference("gene", gene1);
        homologue.setReference("homologue", gene2);
        homologue.addToCollection("evidence", getEvidence());
        homologue.setAttribute("type", taxonId1.equals(taxonId2)? PARALOGUE : ORTHOLOGUE);
        store(homologue);
    }
    
    // genes (in taxonIDs) are always processed
    // homologues are only processed if they are of an organism of interest
    private boolean isValid(String taxonId) {
        if (taxonIds.isEmpty()) {
            // no config so process everything
            return true;
        }
        if (taxonIds.contains(taxonId)) {
            // both are organisms of interest
            return true;
        }
        if (homologues.isEmpty()) {
            // only interested in homologues of interest, so at least one of
            // this pair isn't valid
            return false;
        }
        if (homologues.contains(taxonId)) {
            return true;
        }
        return false;
    }
    
    private String getGene(String ncbiId, String symbol, String taxonId)
            throws ObjectStoreException {
        String identifierType = config.get(taxonId);
        if (StringUtils.isEmpty(identifierType)) {
            identifierType = DEFAULT_IDENTIFIER_FIELD;
        }
        
        String identifier = resolveGene(taxonId, symbol);
        
        if (identifier == null) {
            return null;
        }
        
        String refId = identifiersToGenes.get(new MultiKey(taxonId, identifier));
        if (refId == null) {
            Item item = createItem("Gene");
            item.setAttribute(identifierType, identifier);
            item.setReference("organism", getOrganism(taxonId));
            refId = item.getIdentifier();
            identifiersToGenes.put(new MultiKey(taxonId, identifier), refId);
            store(item);
        }
        return refId;
    }
    
    private String getEvidence() throws ObjectStoreException {
        if (evidenceRefId == null) {
            Item item = createItem("OrthologueEvidenceCode");
            item.setAttribute("abbreviation", EVIDENCE_CODE_ABBR);
            item.setAttribute("name", EVIDENCE_CODE_NAME);
            try {
                store(item);
            } catch (ObjectStoreException e) {
                throw new ObjectStoreException(e);
            }
            String refId = item.getIdentifier();

            item = createItem("OrthologueEvidence");
            item.setReference("evidenceCode", refId);
            try {
                store(item);
            } catch (ObjectStoreException e) {
                throw new ObjectStoreException(e);
            }

            evidenceRefId = item.getIdentifier();
        }
        return evidenceRefId;
    }
    
    private String resolveGene(String taxonId, String identifier) {
        if (rslv == null || !rslv.hasTaxon(taxonId)) {
            // no id resolver available, so return the original identifier
            return identifier;
        }
        int resCount = rslv.countResolutions(taxonId, identifier);
        if (resCount != 1) {
            LOG.info("RESOLVER: failed to resolve fly gene to one identifier, ignoring gene: "
                     + identifier + " count: " + resCount + " FBgn: "
                     + rslv.resolveId(taxonId, identifier));
            return null;
        }
        return rslv.resolveId(taxonId, identifier).iterator().next();
    }
    
    protected class GeneRecord {
        protected String geneRefId;
        protected String taxonId;
        public GeneRecord(String geneRefId, String taxonId) {
            this.geneRefId = geneRefId;
            this.taxonId = taxonId;
        }
    }
}