package devietti;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.ObjectMetadata;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ArchConfMap implements RequestHandler<Void, String> {

    private static final String S3_BUCKET = "archconfmap.com";
    private static final String S3_KEY = "index.html";

    private static final String[] CONFERENCE_NAMES = {"ISCA", "HPCA", "MICRO", "ASPLOS", "PLDI",
            "CGO", "SPLASH", "PPoPP", "ICS", "PACT", "ICPP", "CC", "WIVOSCA"};

    private static final DateTimeFormatter cfpDateFormat = DateTimeFormat.forPattern("MMM d, yyyy");
    private static final DateTimeFormatter monthFormat = DateTimeFormat.forPattern("MMM");
    private static final DateTimeFormatter dayFormat = DateTimeFormat.forPattern("d");
    private static final DateTimeFormatter yearFormat = DateTimeFormat.forPattern("yyyy");

    static {
        Arrays.sort(CONFERENCE_NAMES);
    }

    public static void main(String[] args) {
        doit();
    }

    private static void doit() {

        try (final InputStream stream =
                     ArchConfMap.class.getClassLoader().getResourceAsStream("index-template.html")) {
            // c/o http://stackoverflow.com/questions/309424/read-convert-an-inputstream-to-a-string
            java.util.Scanner s = new java.util.Scanner(stream).useDelimiter("\\A");
            assert s.hasNext();
            String indexHtml = s.next();

            // insert names of conferences we track
            indexHtml = indexHtml.replace("%%CONFNAMES%%", getNames());

            // insert details for each conference
            List<Conf> confs = getconfs();
            String addConfCalls = "";
            for (Conf c : confs) {
                // addConf(name, dates, url, wikicfpEventId, loc, dl, due)
                addConfCalls += String.format("addConf(\"%s\",\"%s\",\"%s\",\"%d\",\"%s\",\"%s\",\"%s\");%n",
                        c.name, c.dates, c.url, c.eventid, c.location, cfpDateFormat.print(c.deadline), c.deadlineStatus);
            }
            indexHtml = indexHtml.replace("%%ADDCONFCALLS%%", addConfCalls);

//            PrintWriter out = new PrintWriter("/Users/devietti/index-test.html");
//            out.println(indexHtml);
//            out.close();

            // write result to S3
            try {
                final AmazonS3 s3 = new AmazonS3Client();
                final byte[] byteArray = indexHtml.getBytes(StandardCharsets.UTF_8);
                InputStream indexHtmlIS = new ByteArrayInputStream(byteArray);
                ObjectMetadata om = new ObjectMetadata();
                om.setContentType("text/html");
                om.setContentLength(byteArray.length);
                s3.putObject(S3_BUCKET, S3_KEY, indexHtmlIS, om);
                debug("Finished uploading index.html to S3");
            } catch (AmazonClientException e) {
                e.printStackTrace();
            }

        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private static String getNames() {
        String[] allButLast = Arrays.copyOf(CONFERENCE_NAMES, CONFERENCE_NAMES.length - 1);
        String str = String.join(", ", (CharSequence[]) allButLast);
        return str + " and " + CONFERENCE_NAMES[CONFERENCE_NAMES.length - 1];
    }

    /** Fetch info for all tracked conferences from WikiCFP */
    private static List<Conf> getconfs() {
        // pull conference info from WikiCFP
        List<Conf> confs = new LinkedList<>();

        // NB: WikiCFP's search only recognizes the first ~7 terms, so we make multiple requests
        final int BATCH_SIZE = 6;
        for (int i = 0; i < CONFERENCE_NAMES.length; i += BATCH_SIZE) {
            String[] slice = Arrays.copyOfRange(CONFERENCE_NAMES, i,
                    Math.min(i + BATCH_SIZE, CONFERENCE_NAMES.length));
            List<Conf> ci = getConfInfo(Arrays.asList(slice));
            if (null != ci) {
                confs.addAll(ci);
            }
        }

        // sort conferences by start date
        Collections.sort(confs);

        List<Conf> toRemove = new LinkedList<>();
        for (Conf c : confs) {
            DateTime now = new DateTime();
            // conference is already over; don't display it
            if (c.end.isBefore(now)) {
                toRemove.add(c);
                debug("Conference already over: " + c.name);
                continue;
            }

            c.deadlineStatus = "pre";
            if (c.deadline.isBefore(now)) {
                c.deadlineStatus = "post";
            }
        }
        confs.removeAll(toRemove);

        return confs;
    }

    /** Fetch info for a list of conferences from WikiCFP */
    private static List<Conf> getConfInfo(List<String> confs) {
        String query = String.join("+", confs);
        debug("Searching for conferences: " + query);
        List<Conf> results = new LinkedList<>();

      /*
       * NB: year=f returns hits for this year and future years. This is exactly what we want, since
       * we automatically discard conferences that have already happened.
       */
        Document doc = getURL("http://www.wikicfp.com/cfp/servlet/tool.search?year=f&q=" + query);
        if (null == doc) {
            error("Couldn't query wikiCFP " + query);
            return null;
        }

        Elements rows = doc.select("div[class=contsec] table table tr");
        for (Iterator<Element> iter = rows.iterator(); iter.hasNext(); ) {
            final Element firstRow = iter.next();
            final Elements confName = firstRow.select("td a");
            if (confName.isEmpty()) continue;

            final Conf conf = new Conf();

            // make sure we match one of the conferences we're interested in
            String cn = confName.first().text().split(" ")[0];
            int found = Arrays.binarySearch(CONFERENCE_NAMES, cn);
            if (found < 0) continue; // not found

            final String confFullName = firstRow.select("td").get(1).text();
            // don't match other ICS conferences, eg Information, Communication, Society
            if (CONFERENCE_NAMES[found].equals("ICS")) {
                if (!confFullName.toLowerCase().contains("supercomputing")) {
                    continue;
                }
            }
            // don't match other CC conferences, eg Creative Construction
            if (CONFERENCE_NAMES[found].equals("CC")) {
                if (!confFullName.toLowerCase().contains("compiler")) {
                    continue;
                }
            }

            conf.name = confName.first().text();
            debug("Matched name for " + conf.name);

         /*
          * we found a hit! The conference information is split across two <tr> table elements.
          * Conference name and link to cfp are in the first <tr>, and dates, location and deadline
          * in the second.
          */

            final Element secondRow = iter.next();
            String dates = secondRow.select("td").first().text();
            String startDate = dates.substring(0, dates.indexOf('-')).trim();
            conf.start = cfpDateFormat.parseDateTime(startDate);
            conf.end = cfpDateFormat.parseDateTime(dates.substring(dates.indexOf('-') + 1).trim());

            conf.dates = cfpDateFormat.print(conf.start) + " - " + cfpDateFormat.print(conf.end);
            if (conf.start.year().equals(conf.end.year())
                    && conf.start.monthOfYear().equals(conf.end.monthOfYear())) {
                conf.dates = monthFormat.print(conf.start) + " " + dayFormat.print(conf.start) + "-"
                        + dayFormat.print(conf.end) + " " + yearFormat.print(conf.start);
            }

            String deadline = secondRow.select("td").get(2).text().trim();
            if (deadline.contains("(")) { // abstract deadline may be in parentheses
                deadline = deadline.substring(0, deadline.indexOf('(')).trim();
            }
            conf.deadline = cfpDateFormat.parseDateTime(deadline);

            conf.url = "http://www.wikicfp.com" + confName.attr("href");

            conf.location = secondRow.select("td").get(1).text();

            /*
             * extract the WikiCFP eventid from the link so we can pull the
             * cfp page and get the direct conference site link.
             */
            try {
                List<NameValuePair> result = URLEncodedUtils.parse(new URI(conf.url), "UTF-8");
                for (NameValuePair nvp : result) {
                    if (nvp.getName().equals("eventid")) {
                        conf.eventid = Integer.valueOf(nvp.getValue());
                        String extUrl = getExternalConfWebsite(conf.eventid);
                        conf.url = (null == extUrl) ? conf.url : extUrl;
                        break;
                    }
                }

            } catch (NumberFormatException e) {
                error("invalid event id in url: " + conf.url);
                continue;
            } catch (URISyntaxException e) {
                e.printStackTrace();
                continue;
            }

            debug("Found info for " + conf.toString());
            results.add(conf);
        }
        return results;
    }

    /** Parse a URL (presumed to be pointing at an HTML page) into a Jsoup Document */
    private static Document getURL(String url) {
        Scanner s = null;
        try {

            s = new Scanner(new URL(url).openStream(), "UTF-8");
            return Jsoup.parse(s.useDelimiter("\\A").next());

        } catch (IOException e) {
            e.printStackTrace();
            error("error fetching URL " + url);
            return null;
        } finally {
            if (s != null) s.close();
        }
    }

    /**
     * Returns the URL of the external conference website (not the WikiCFP page) for the given
     * eventid.
     */
    private static String getExternalConfWebsite(int eid) {
        // pull down the CFP
        Document cfp = getURL("http://www.wikicfp.com/cfp/servlet/event.showcfp?eventid=" + String.valueOf(eid));
        if (null == cfp) return null;

        for (Element a : cfp.select("tr td[align=center] a")) {
            Element td = a.parent();
            if (td.text().contains("Link:") && a.hasAttr("href") &&
                    (a.attr("href").contains("http://") || a.attr("href").contains("https://"))) {
                // got the link!
                return a.attr("href");
            }
        }

        error("no matching link for eventid " + String.valueOf(eid));
        return null;
    }

    private static void error(String s) {
        System.err.println(s);
    }

    private static void debug(String s) {
        System.out.println(s);
    }

    @Override
    public String handleRequest(Void aVoid, Context context) {
        doit();
        return "ArchConfMap index.html file regenerated";
    }

    private static class Conf implements Comparable<Conf> {
        String name;
        DateTime start;
        DateTime end;
        String dates;
        String url;
        int eventid;
        String location;
        DateTime deadline;
        String deadlineStatus;

        @Override
        public int compareTo(Conf c) {
            return start.compareTo(c.start);
        }

        @Override
        public String toString() {
            return String.format("%s %s %s %s %s %s", name, dates, url, location, deadline, deadlineStatus);
        }
    }


}
