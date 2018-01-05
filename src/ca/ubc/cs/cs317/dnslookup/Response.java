package ca.ubc.cs.cs317.dnslookup;

import java.util.ArrayList;

/**
 * Created by evanmartin on 2017-11-03.
 */
public class Response {
    // some indexes and lengths
    private static final int HEADER_LENGTH = 12;
    private static final int ANSCOUNT_START_INDEX = 6;
    private static final int NSCOUNT_START_INDEX = 8;
    private static final int ARCOUNT_START_INDEX = 10;
    private static final int QUERY_RESOURCE_COUNT_LENGTH = 2;
    private static final int QUESTION_START_INDEX = HEADER_LENGTH;

    // authoritative bit
    private static final byte[] AA_BIT = {1};

    // queryID to match a response to a query
    private int queryID;
    private int replyCode;
    private int ans_count;
    private int ns_count;
    private int ar_count;
    private boolean authoritative = false;

    // response codes
    public static final int RCODE_NO_ERROR = 0;
    public static final int RCODE_FORMAT_ERROR = 1;
    public static final int RCODE_SERVER_ERROR = 2;
    public static final int RCODE_NAME_ERROR = 3;
    public static final int RCODE_NOT_IMPLEMENTED_ERROR = 4;
    public static final int RCODE_REFUSED_ERROR = 5;

    // message compression
    private static final byte[] MESSAGE_COMPRESSION = {1, 1};

    ArrayList<ResourceRecord> answers = new ArrayList();
    ArrayList<ResourceRecord> servers = new ArrayList();
    ArrayList<ResourceRecord> additionals = new ArrayList();

    /**
     * CONSTRUCTOR: Response Object
     * @param responseBytes raw data from the response
     */
    public Response(byte[] responseBytes){
        queryID = parseByteToInt(responseBytes, 0, 2);      // query transaction ID that invoked this response
        authoritative = (checkBit(responseBytes[2], 5, AA_BIT)); // T/F authoritative bit of the response
        replyCode = parseBitsToInt(responseBytes[3], 4, 4); // reply code for error checking
        ans_count = parseByteToInt(responseBytes, ANSCOUNT_START_INDEX, QUERY_RESOURCE_COUNT_LENGTH);
        ns_count = parseByteToInt(responseBytes, NSCOUNT_START_INDEX, QUERY_RESOURCE_COUNT_LENGTH);
        ar_count = parseByteToInt(responseBytes, ARCOUNT_START_INDEX, QUERY_RESOURCE_COUNT_LENGTH);

        // get the start index of the ANSWER section (this is either before ANSWER section or before the NAMESERVER section)
        int questionEndIndex = QUESTION_START_INDEX + getNameLength(responseBytes, QUESTION_START_INDEX);

        // get the answers
        int answerStartIndex = questionEndIndex + 4;
        for (int i = 0; i < ans_count; i++) {
            ResourceRecord record = getResourceRecord(responseBytes, answerStartIndex);
            answers.add(record);
            answerStartIndex += record.getRecordLength();
        }

        // get the name servers
        int serverStartIndex = answerStartIndex;
        for (int i = 0; i < ns_count; i++) {
            ResourceRecord record = getResourceRecord(responseBytes, serverStartIndex);
            servers.add(record);
            serverStartIndex += record.getRecordLength();
        }

        // get the additional records
        int additionalStartIndex = serverStartIndex;
        for (int i = 0; i < ar_count; i++) {
            ResourceRecord record = getResourceRecord(responseBytes, additionalStartIndex);
            additionals.add(record);
            additionalStartIndex += record.getRecordLength();
        }
    }

    /**
     * parses the response bytes from raw response data and returns a ResourceRecord Object
     * @param responseBytes raw data to be parsed
     * @param startIndex starting index of the resource record data in the responseBytes
     * @return ResourceRecord Object that can be queried for useful information
     */
    public static ResourceRecord getResourceRecord(byte[] responseBytes, int startIndex){
        String name = parseName(responseBytes, startIndex);
        int nameLength = getNameLength(responseBytes, startIndex);
        int type_code = parseByteToInt(responseBytes, startIndex+nameLength+ResourceRecord.TYPE_NAMELENGTH_OFFSET, ResourceRecord.TYPE_LENGTH);
        int cl = parseByteToInt(responseBytes, startIndex+nameLength+ResourceRecord.CLASS_NAMELENGTH_OFFSET, ResourceRecord.CLASS_LENGTH);
        int ttl = parseByteToInt(responseBytes, startIndex+nameLength+ResourceRecord.TTL_NAMELENGTH_OFFSET, ResourceRecord.TTL_LENGTH);
        int rdlength = parseByteToInt(responseBytes, startIndex+nameLength+ResourceRecord.RDLENGTH_NAMELENGTH_OFFSET, ResourceRecord.RDLENGTH_LENGTH);
        String textResult = parseRData(responseBytes, startIndex+nameLength+ResourceRecord.RDATA_NAMELENGTH_OFFSET, type_code, cl);
        RecordType recordType = RecordType.getByCode(type_code);
        int recordLength = nameLength + 10 + rdlength;

        return new ResourceRecord(name, recordType, ttl, textResult, recordLength);
    }

    /**
     * parse the name of the resource record
     * @param responseBytes raw data from the response
     * @param i index to start parsing from
     * @return the name of the resource record, e.g. 'ca', 'com', 'org', etc.
     */
    public static String parseName(byte[] responseBytes, int i){
        ArrayList<String> labels = new ArrayList<>();

        if (responseBytes[i] == 0){
            return null;
        }

        while(responseBytes[i] != 0){
            if(checkBit(responseBytes[i], 0, MESSAGE_COMPRESSION)){
                int offset = parseByteToInt(responseBytes, i, 2) - 49152;
                labels.add(parseName(responseBytes, offset));
                break;
            } else{
                int labelLength = parseByteToInt(responseBytes, i, 1);
                labels.add(new String(responseBytes, i+1, labelLength));
                i += 1 + labelLength;
            }
        }
        return joinStringArrayList(labels, ".");
    }

    /**
     * parses the responseBytes for the names of the next node
     * @param responseBytes raw data to parse
     * @param i index to start parsing
     * @param type_code record type code of the resource record
     * @param cl class
     * @return name of the next node
     */
    private static String parseRData(byte[] responseBytes, int i, int type_code, int cl) {
        if (type_code == ResourceRecord.TYPE_A && cl == ResourceRecord.CLASS_IP) {
            return parseIPv4(responseBytes, i);
        } else if ((type_code == ResourceRecord.TYPE_NS || type_code == ResourceRecord.TYPE_CNAME) && cl == ResourceRecord.CLASS_IP) {
            return parseName(responseBytes, i);
        } else if (type_code == ResourceRecord.TYPE_AAAA && cl == ResourceRecord.CLASS_IP) {
            return parseIPv6(responseBytes, i);
        }
        return "";
    }

    /**
     * parse an IPv4 address
     * @param bytes raw data to parse the IP address from
     * @param i index to start parsing from
     * @return IPv4 address as a String in the form: "X.X.X.X"
     */
    private static String parseIPv4(byte[] bytes, int i) {
        ArrayList<String> fields = new ArrayList<String>();

        for (int j = 0; j < 4; j++) {
            fields.add(Integer.toString(parseByteToInt(bytes, i+j, 1)));
        }

        return joinStringArrayList(fields, ".");
    }

    /**
     * parse an IPv6 address
     * @param bytes raw data to parse the IP address from
     * @param i index to start parsing from
     * @return IPv6 address as a String in the form: "X:X:X:X:X:X:X:X"
     */
    private static String parseIPv6(byte[] bytes, int i) {
        ArrayList<String> fields = new ArrayList<String>();

        for (int j = 0; j < 8; j++) {
            fields.add(Integer.toString(parseByteToInt(bytes, i+(j*2), 2), 16));
        }

        return joinStringArrayList(fields, ":");
    }

    private static int getNameLength(byte[] responseData, int i) {
        int length = 0;
        while (true) {
            int labelLength = parseByteToInt(responseData, i, 1);

            if (labelLength == 0) {
                return length + 1;
            }

            if (checkBit(responseData[i], 0, MESSAGE_COMPRESSION)) {
                return length + 2;
            }

            length += 1 + labelLength;
            i += 1 + labelLength;
        }
    }

    private static boolean checkBit(byte b, int i, byte[] bits) {
        for (int j = 0; j < bits.length; j++) {
            if (getBit(b, i+j) != bits[j]) {
                return false;
            }
        }

        return true;
    }

    private static int parseBitsToInt(byte b, int i, int l) {
        int value = 0;
        for (int j = 0; j < l; j++) {
            value += getBit(b, (i+j)) * Math.pow(2, l-j-1);
        }
        return value;
    }

    public static int parseByteToInt(byte[] bytes, int i, int l) {
        int value = 0;

        for (int j = 0; j < l; j++) {
            value += parseByteToUnsignedInt(bytes[i+j]) * Math.pow(256, l-j-1);
        }

        return value;
    }

    public static int parseByteToUnsignedInt(byte b) {
        return b & 0xFF;
    }

    private static int getBit(byte b, int i) {
        return (b >> (7-i)) & 1;
    }

    public static String joinStringArrayList(ArrayList<String> strings, String sep) {
        String str = strings.get(0);

        for (int i = 1; i < strings.size(); i++) {
            str += sep + strings.get(i);
        }

        return str;
    }

    public int getID() {
        return queryID;
    }

    public int getRcode(){
        return replyCode;
    }

    public boolean getAuth() {
        return authoritative;
    }

    public ArrayList<ResourceRecord> getAnswers() {
        return answers;
    }

    public ArrayList<ResourceRecord> getServers() {
        return servers;
    }

    public ArrayList<ResourceRecord> getAdditionals() {
        return additionals;
    }
}
