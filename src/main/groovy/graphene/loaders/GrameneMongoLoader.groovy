package graphene.loaders

import com.mongodb.DBCollection
import com.mongodb.DBCursor
import graphene.mongo.Mongo
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Log4j2
import org.neo4j.graphdb.Label

import java.util.regex.Matcher

/**
 * Created by mulvaney on 10/31/14.
 */
@Log4j2
abstract class GrameneMongoLoader extends Loader {

    abstract long process(Map result)

    abstract String getPath()

    @Override
    void load() {
        Integer start = 0

        DBCollection collection = Mongo.get(path)
        DBCursor data = collection.find()

        while (data.hasNext()) {
            Map taxon = data.next()
            try {
                preprocess(taxon)
                process(taxon)
            } catch(Exception e) {
                String doc = JsonOutput.prettyPrint(JsonOutput.toJson(taxon));
                log.error("Error thrown debugging this document:\n$doc", e)
            }
            if (0 == ++start % 10_000) log.info "$start records processed"
        }

        log.info "$start records processed"
    }

    static preprocess(Map entry) {
        entry.remove('_terms')
        entry.remove('alt_id')
        entry.remove('ancestors')
        entry.synonym = getSynonyms(entry.remove('synonym'))

        Matcher rankMatcher = entry.remove('property_value') =~ /has_rank NCBITaxon:(\w+)/
        if (rankMatcher) {
            entry.rank = ((List<String>) rankMatcher[0])[1]?.capitalize()
            // explicit cast to fail early if we didn't get any match groups
        }
    }


    static Set<String> getSynonyms(synonyms) {
        if (synonyms instanceof String) {
            synonyms = [synonyms]
        }
        synonyms as Set
    }

    void createSynonyms(Long nodeId, def synonyms) {
        // `synonyms` might be a scalar string or a list of strings

        Label nameLabel = labels.Name
        for (String s in synonyms) {
            long synonymNodeId = nodes.getOrCreate(nameLabel, s, batch)
            link(nodeId, synonymNodeId, Rels.SYNONYM)
        }
    }

    static String underscoreCaseToCamelCase(String s) {
        s?.toLowerCase()?.split('_')*.capitalize()?.join('')
    }

    def createXrefs(long nodeId, Map<String, List<String>> xrefs) {
        for(Map.Entry<String, List<String>> xref in xrefs) {
            String type = xref.key
            for(String val in xref.value) {
                createXref(type, val, nodeId)
            }
        }
    }

    def createXrefs(long nodeId, List<String> xrefs) {
        for (String xref in xrefs) {
            if (xref.indexOf(':') > 0) {
                def (String key, String value) = xref.split(':', 2)
                if (key != 'GC_ID') createXref(key, value, nodeId)
            }
        }
    }

    def createXref(String type, String name, Long referrerId) {
        Collection<Label> allLabels = labels.getLabels([type, 'Xref'])
        Map props = [name: name, type: type]

        if (['Reactome', 'VZ', 'http', 'loinc'].contains(type)) {
            String[] splitt = props.name.split(' ', 2)
            props.name = splitt[0]
            if (splitt.length > 1) props.desc = splitt[1]
        }

        Long xrefId = node(referrerId, labels[type], props, allLabels)
        link(referrerId, xrefId, Rels.XREF)
    }
}
