package ca.ubc.cs.cs317.dnslookup;

import java.io.ByteArrayOutputStream;
import java.io.Console;
import java.io.IOException;
import java.net.*;
import java.util.*;

public class DNSLookupService {

    private static final int DEFAULT_DNS_PORT = 53;
    private static final int MAX_INDIRECTION_LEVEL = 10;

    // Record types
    private static final int AAAA = 28;
    private static final int A = 1;
    private static final int NS = 2;
    private static final int CNAME = 5;
    private static final int MX = 15;
    private static final int OTHER = 0;

    private static InetAddress rootServer;
    private static boolean verboseTracing = false;
    private static DatagramSocket socket;

    private static DNSCache cache = DNSCache.getInstance();

    private static Random random = new Random();

    /**
     * Main function, called when program is first invoked.
     *
     * @param args list of arguments specified in the command line.
     */
    public static void main(String[] args) throws UnknownHostException {

        if (args.length != 1) {
            System.err.println("Invalid call. Usage:");
            System.err.println("\tjava -jar DNSLookupService.jar rootServer");
            System.err.println("where rootServer is the IP address (in dotted form) of the root DNS server to start the search at.");
            System.exit(1);
        }

        try {
            rootServer = InetAddress.getByName(args[0]);
            System.out.println("Root DNS server is: " + rootServer.getHostAddress());
        } catch (UnknownHostException e) {
            System.err.println("Invalid root server (" + e.getMessage() + ").");
            System.exit(1);
        }

        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(5000);
        } catch (SocketException ex) {
            ex.printStackTrace();
            System.exit(1);
        }

        Scanner in = new Scanner(System.in);
        Console console = System.console();
        do {
            // Use console if one is available, or standard input if not.
            String commandLine;
            if (console != null) {
                System.out.print("DNSLOOKUP> ");
                commandLine = console.readLine();
            } else
                try {
                    commandLine = in.nextLine();
                } catch (NoSuchElementException ex) {
                    break;
                }
            // If reached end-of-file, leave
            if (commandLine == null) break;

            // Ignore leading/trailing spaces and anything beyond a comment character
            commandLine = commandLine.trim().split("#", 2)[0];

            // If no command shown, skip to next command
            if (commandLine.trim().isEmpty()) continue;

            String[] commandArgs = commandLine.split(" ");

            if (commandArgs[0].equalsIgnoreCase("quit") ||
                    commandArgs[0].equalsIgnoreCase("exit"))
                break;
            else if (commandArgs[0].equalsIgnoreCase("server")) {
                // SERVER: Change root nameserver
                if (commandArgs.length == 2) {
                    try {
                        rootServer = InetAddress.getByName(commandArgs[1]);
                        System.out.println("Root DNS server is now: " + rootServer.getHostAddress());
                    } catch (UnknownHostException e) {
                        System.out.println("Invalid root server (" + e.getMessage() + ").");
                        continue;
                    }
                } else {
                    System.out.println("Invalid call. Format:\n\tserver IP");
                    continue;
                }
            } else if (commandArgs[0].equalsIgnoreCase("trace")) {
                // TRACE: Turn trace setting on or off
                if (commandArgs.length == 2) {
                    if (commandArgs[1].equalsIgnoreCase("on"))
                        verboseTracing = true;
                    else if (commandArgs[1].equalsIgnoreCase("off"))
                        verboseTracing = false;
                    else {
                        System.err.println("Invalid call. Format:\n\ttrace on|off");
                        continue;
                    }
                    System.out.println("Verbose tracing is now: " + (verboseTracing ? "ON" : "OFF"));
                } else {
                    System.err.println("Invalid call. Format:\n\ttrace on|off");
                    continue;
                }
            } else if (commandArgs[0].equalsIgnoreCase("lookup") ||
                    commandArgs[0].equalsIgnoreCase("l")) {
                // LOOKUP: Find and print all results associated to a name.
                RecordType type;
                if (commandArgs.length == 2)
                    type = RecordType.A;
                else if (commandArgs.length == 3)
                    try {
                        type = RecordType.valueOf(commandArgs[2].toUpperCase());
                    } catch (IllegalArgumentException ex) {
                        System.err.println("Invalid query type. Must be one of:\n\tA, AAAA, NS, MX, CNAME");
                        continue;
                    }
                else {
                    System.err.println("Invalid call. Format:\n\tlookup hostName [type]");
                    continue;
                }
                findAndPrintResults(commandArgs[1], type);
            } else if (commandArgs[0].equalsIgnoreCase("dump")) {
                // DUMP: Print all results still cached
                cache.forEachNode(DNSLookupService::printResults);
            } else {
                System.err.println("Invalid command. Valid commands are:");
                System.err.println("\tlookup fqdn [type]");
                System.err.println("\ttrace on|off");
                System.err.println("\tserver IP");
                System.err.println("\tdump");
                System.err.println("\tquit");
                continue;
            }

        } while (true);

        socket.close();
        System.out.println("Goodbye!");
    }

    /**
     * Finds all results for a host name and type and prints them on the standard output.
     *
     * @param hostName Fully qualified domain name of the host being searched.
     * @param type     Record type for search.
     */
    private static void findAndPrintResults(String hostName, RecordType type) throws UnknownHostException {
        DNSNode node = new DNSNode(hostName, type);
        printResults(node, getResults(node, 0));
    }

    /**
     * Finds all the result for a specific node.
     *
     * @param node             Host and record type to be used for search.
     * @param indirectionLevel Control to limit the number of recursive calls due to CNAME redirection.
     *                         The initial call should be made with 0 (zero), while recursive calls for
     *                         regarding CNAME results should increment this value by 1. Once this value
     *                         reaches MAX_INDIRECTION_LEVEL, the function prints an error message and
     *                         returns an empty set.
     * @return A set of resource records corresponding to the specific query requested.
     */
    private static Set<ResourceRecord> getResults(DNSNode node, int indirectionLevel)  {
        if (indirectionLevel > MAX_INDIRECTION_LEVEL) {
            System.err.println("Maximum number of indirection levels reached.");
            return Collections.emptySet();
        }
        // TODO To be completed by the student

        // if the node is cached, return the cached results without issuing a lookup
        if (!cache.getCachedResults(node).isEmpty()){
            return cache.getCachedResults(node);
        }

        // keep track of the node we initially queried, to be used in the situation where we resolve CNAMEs
        DNSNode initialNode = node;

        // query the root server and name servers until we reach an authoritative server, then cache the answer from the
        // authoritative server, also caching all additional information along the way
        try {
            retrieveResultsFromServer(node, initialNode, rootServer);
        } catch (UnknownHostException e) {
            System.out.printf("%-30s %-5s %-8d %s\n", node.getHostName(),
                    node.getType(), -1, "0.0.0.0");
            System.out.println("here");
        }

        // after we have retrieve the results from the root servers->nameservers->authoritative servers, we query the
        // cache for node corresponding to the one given as input, this is a mapping between hostName and IP
        return cache.getCachedResults(node);
    }


    /**
     * Retrieves DNS results from a specified DNS server. Queries are sent in iterative mode,
     * and the query is repeated with a new server if the provided one is non-authoritative.
     * Results are stored in the cache.
     *
     * @param node   Host name and record type to be used for the query.
     * @param server Address of the server to be used for the query.
     */
    private static void retrieveResultsFromServer(DNSNode node, DNSNode initialNode, InetAddress server) throws UnknownHostException {
        // TODO To be completed by the student

        // get T/F value for IPv6
        boolean isIPv6 = (node.getType().getCode() == AAAA);

        // randomly generate 16-bit transaction id
        byte[] id = new byte[2];
        random.nextBytes(id);

        // allocate buffer for response
        byte[] buf = new byte[512];

        // create a packet to hold the response
        DatagramPacket responsePacket = new DatagramPacket(buf, buf.length);
        byte[] responseBytes;

        // send out the query
        sendQuery(socket, id, node, server);

        // print the query
        printQueryTrace(id, node.getHostName(), isIPv6, server);

        // get the response
        responseBytes = getResponse(socket, responsePacket, id);

        // if the response is null then something went wrong, returning null will cascade down and printErrorResponse
        // method will be called
        if (responseBytes == null){
            return;
        }

        // parse the response
        Response response = new Response(responseBytes);
        int responseID = response.getID();                                             // transaction ID
        boolean responseAuth = response.getAuth();                                     // is this server authoritative? (T/F)
        int responseRcode = response.getRcode();
        ArrayList<ResourceRecord> answers = response.getAnswers();                     // answer section
        ArrayList<ResourceRecord> nameservers = response.getServers();                 // nameserver section
        ArrayList<ResourceRecord> additionalInformations = response.getAdditionals();  // additional information section

        // check for errors
        switch (responseRcode) {
            case Response.RCODE_NAME_ERROR:
            case Response.RCODE_REFUSED_ERROR:
            case Response.RCODE_SERVER_ERROR:
                return;
        }

        // print the response
        printResponseTrace(responseID, responseAuth, answers, nameservers, additionalInformations);

        // cache the answer section of the response
        for (ResourceRecord answer: answers){
            cache.addResult(answer);
        }
        // cache the nameserver section of the response
        for (ResourceRecord nameserver: nameservers){
            cache.addResult(nameserver);
        }
        // cache the additional information section of the response
        for (ResourceRecord additionalInformation: additionalInformations){
            cache.addResult(additionalInformation);
        }

        // if the answer section is empty, then the server that we queried is not an authoritative server for the
        // hostName, so we send a query to one of the nameservers the current server included in its response, we
        // arbitrarily select the first nameserver in the nameserver section to query
        if (answers.isEmpty() && !nameservers.isEmpty()){
            InetAddress nextServer = InetAddress.getByName(nameservers.get(0).getTextResult());
            retrieveResultsFromServer(node, initialNode, nextServer); // recursive call
        }

        // if the answer section is not empty, we have a mapping from our hostname to an IP or we have a mapping from
        // our hostname to a CNAME, in the latter case we need to follow the CNAME as the new hostname
        if (!answers.isEmpty()){
            if (!node.equals(initialNode)){
                // when the node we are querying does not match our initial node, we know that we are querying with a
                // CNAME node, for each result we get from a CNAME node, create a resource record mapping the initial
                // node's hostname to the current node's resource record.
                for (ResourceRecord answer: answers) {
                    ResourceRecord augmentedResourceRecord = answer;
                    augmentedResourceRecord.changeHostName(initialNode.getHostName());
                    cache.addResult(augmentedResourceRecord);
                }
            }
            // if we have results in the answer section, then we might have CNAMEs to deal with, in which case we follow
            // the CNAMEs down to an IP
            for (ResourceRecord answer: answers){
                RecordType answerType = RecordType.getByCode(answer.getType());
                if (answerType.equals(RecordType.getByCode(CNAME))){
                    String cnameHostName = answer.getTextResult();
                    DNSNode cnameNode = new DNSNode(cnameHostName, RecordType.A);
                    retrieveResultsFromServer(cnameNode, node, rootServer);
                }
            }
        }
    }

    /**
     * sends a DNS query to the node at port 53
     * @param socket where we send the query from
     * @param id transaction id of our query
     * @param node host name and resource record type of the query
     * @param server where we are sending the query to
     * @return the transaction id of the query (later used to check equality with response transaction id)
     */
    private static byte[] sendQuery(DatagramSocket socket, byte[] id, DNSNode node, InetAddress server) {
        // write the query
        byte[] query = writeQuery(id, node);

        // create the packet
        DatagramPacket packet = new DatagramPacket(query, query.length, server, DEFAULT_DNS_PORT);

        // send the packet
        try {
            socket.send(packet);
        } catch (IOException e) {
            printErrorResponse(node);
            return null;
        }

        // return the transaction id
        return id;
    }

    /**
     * writes the HEADER, NAME, TYPE and CLASS sections of a query
     * @param id transaction id of the query
     * @param node node of which we are querying
     * @return byte array representing the query
     */
    private static byte[] writeQuery(byte[] id, DNSNode node){
        // create a new output stream for the contents of the query
        ByteArrayOutputStream queryOutputStream = new ByteArrayOutputStream();

        // write HEADER section
        writeHeader(queryOutputStream, id);

        // write NAME section
        int questionLength = 0;
        String[] segs = node.getHostName().split("\\."); // seg is an alphabetical artifact in hostName eg. "google", "com"
        for (String seg:segs) {                     // for each seg, write it to query and update the query length
            byte[] segBytes = seg.getBytes();
            int segLength = segBytes.length;
            queryOutputStream.write(segLength);                   // write the length of the seg
            queryOutputStream.write(segBytes, 0, segLength);      // write the actual seg
            questionLength += 1 + segLength;                      // keep track of the question length
        }
        queryOutputStream.write(0); // write a 0 to signify we are done,
        questionLength += 1;        // and increment length accordingly

        // write TYPE section
        boolean isIPv6 =  false;
        if (node.getType().getCode() == AAAA){
            isIPv6 = true;
        }
        queryOutputStream.write(0);
        if (isIPv6) {           // write a 28 if IPv6, write a 1 if IPv4
            queryOutputStream.write(28);
        } else {
            queryOutputStream.write(1);
        }
        questionLength += 2;

        // write CLASS section
        queryOutputStream.write(0);
        queryOutputStream.write(1);
        questionLength += 2;

        // return the query
        return queryOutputStream.toByteArray();
    }

    /**
     * writes the header for a standard query
     * @param queryOutputStream where we are writing to
     * @param id the transaction ID to be included in the header
     */
    private static void writeHeader(ByteArrayOutputStream queryOutputStream, byte[] id) {
        // TRANSACTION ID
        queryOutputStream.write(id, 0, 2);
        // FLAGS
        queryOutputStream.write(0);
        queryOutputStream.write(0);
        // QUESTIONS (there is one question)
        queryOutputStream.write(0);
        queryOutputStream.write(1);
        // ANSWER RRs (there are no answers)
        queryOutputStream.write(0);
        queryOutputStream.write(0);
        // AUTHORITY RRs (there are none)
        queryOutputStream.write(0);
        queryOutputStream.write(0);
        // ADDITIONAL RRs (there are none)
        queryOutputStream.write(0);
        queryOutputStream.write(0);
    }

    /**
     * retrieves the corresponding response of the given transaction id from the socket
     * @param socket socket to receive response from
     * @param responsePacket response packet to be received
     * @param id transaction to match with the query transaction id
     * @return raw response data as an array of bytes
     */
    private static byte[] getResponse(DatagramSocket socket, DatagramPacket responsePacket, byte[] id){
        // create an array to hold the response
        byte[] responseBytes;

        // attempt to receive the response, return null on timeout or IOException
        while (true) {
            try {
                socket.receive(responsePacket);
            } catch (SocketTimeoutException e) {
                return null; // this cascades down and ends up causing the printErrorResponse method to get called
            } catch (IOException e) {
                return null; // this cascades down and ends up causing the printErrorResponse method to get called
            }

            // store the bytes from the response in the array
            responseBytes = responsePacket.getData();

            // check if the transaction IDs match, exit loop when they do
            if (responseBytes[0] == id[0] && responseBytes[1] == id[1]) {
                break;
            }
        }

        // return the bytes of the response held in a byte array
        return responseBytes;
    }

    private static void verbosePrintResourceRecord(ResourceRecord record, int rtype) {
        if (verboseTracing)
            System.out.format("       %-30s %-10d %-4s %s\n",
                    record.getHostName(),
                    record.getTTL(),
                    RecordType.getByCode(record.getType()),
                    record.getTextResult());
    }

    /**
     * Prints the result of a DNS query.
     * @param node    Host name and record type used for the query.
     * @param results Set of results to be printed for the node.
     */
    private static void printResults(DNSNode node, Set<ResourceRecord> results) {
        if (results.isEmpty())
            System.out.printf("%-30s %-5s %-8d %s\n", node.getHostName(),
                    node.getType(), -1, "0.0.0.0");
        for (ResourceRecord record : results) {
            System.out.printf("%-30s %-5s %-8d %s\n", node.getHostName(),
                    node.getType(), record.getTTL(), record.getTextResult());
        }
    }

    /**
     * prints the response trace as specified by the assignment description
     * @param id transaction id of the response
     * @param isAuth is the response authoritative?
     * @param answers answer resource records
     * @param servers server resource records
     * @param additionalInformations additional information resource records
     */
    private static void printResponseTrace(int id, boolean isAuth, ArrayList<ResourceRecord> answers, ArrayList<ResourceRecord> servers, ArrayList<ResourceRecord> additionalInformations) {
        if (verboseTracing){
            System.out.format("Response ID: %d Authoritative = %b\n", id, isAuth);

            System.out.format("  Answers (%d)\n", answers.size());
            for (ResourceRecord answer : answers) {
                verbosePrintResourceRecord(answer, answer.getType());
            }

            System.out.format("  Nameservers (%d)\n", servers.size());
            for (ResourceRecord server : servers) {
                verbosePrintResourceRecord(server, server.getType());
            }

            System.out.format("  Additional Information (%d)\n", additionalInformations.size());
            for (ResourceRecord additional : additionalInformations) {
                verbosePrintResourceRecord(additional, additional.getType());
            }
        }
    }

    /**
     * prints a trace of the query that was just sent to a server
     * @param id transaction id of the query
     * @param hostName name we are querying for
     * @param isIPv6 is the query for an IPv6 address?
     * @param server server that we are sending to
     */
    private static void printQueryTrace(byte[] id, String hostName, boolean isIPv6, InetAddress server) {
        if (verboseTracing){
            System.out.format("\n\nQuery ID     %d %s  %s --> %s\n", Response.parseByteToInt(id, 0, 2), hostName, (isIPv6) ? "AAAA" : "A", server.getHostAddress());
        }
    }

    /**
     * prints the error response specified in the assignment description
     * @param node the node that queried for but invoked an error
     */
    private static void printErrorResponse(DNSNode node){
        System.out.printf("%-30s %-5s %-8d %s\n", node.getHostName(),
                node.getType(), -1, "0.0.0.0");
    }

}
