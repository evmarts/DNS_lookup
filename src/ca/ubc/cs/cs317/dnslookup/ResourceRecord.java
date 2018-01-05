package ca.ubc.cs.cs317.dnslookup;

import java.io.Serializable;
import java.net.InetAddress;
import java.util.Date;

/** A resource record corresponds to each individual result returned by a DNS response. It links
 * a DNS node (host name and record type) to either an IP address (e.g., A or AAAA records) or
 * a textual response (e.g., CNAME or NS records). A TTL (time-to-live) field is also specified,
 * and is represented by an expiration time calculated as a delta from the current time.
 */
public class ResourceRecord implements Serializable {

    public static final int TYPE_NAMELENGTH_OFFSET = 0;
    public static final int CLASS_NAMELENGTH_OFFSET = 2;
    public static final int TYPE_LENGTH = 2;
    public static final int CLASS_LENGTH = 2;
    public static final int TTL_NAMELENGTH_OFFSET = 4;
    public static final int TTL_LENGTH = 4;
    public static final int RDLENGTH_NAMELENGTH_OFFSET = 8;
    public static final int RDLENGTH_LENGTH = 2;
    public static final int RDATA_NAMELENGTH_OFFSET = 10;

    public static final int TYPE_A = 1;
    public static final int TYPE_NS = 2;
    public static final int TYPE_CNAME = 5;
    public static final int TYPE_AAAA = 28;

    public static final int CLASS_IP = 1;

    private int recordLength;

    private DNSNode node;
    private Date expirationTime;
    private String textResult;
    private InetAddress inetResult;

    /**
     * CONSTRUCTOR: ResourceRecord Object
     * @param hostName hostname of the resource record
     * @param type type of the resource record
     * @param ttl time-to-live of the resource record
     * @param textResult name of the server, e.g. "a0.org.afilias-nst.info", "c-ca.servers.ca", "a.gtld-servers.net"
     */
    public ResourceRecord(String hostName, RecordType type, long ttl, String textResult, int recordLength) {
        this.node = new DNSNode(hostName, type);
        this.expirationTime = new Date(System.currentTimeMillis() + (ttl * 1000));
        this.textResult = textResult;
        this.inetResult = null;
        this.recordLength = recordLength;
    }

    /** The TTL for this record. It is returned based on the (ceiling of the) number of seconds
     * remaining until this record expires. The TTL returned by this method will only match the
     * TTL obtained from the DNS server in the first second from the time this record was
     * created.
     *
     * @return The number of seconds, rounded up, until this record expires.
     */
    public long getTTL() {
        return (expirationTime.getTime() - System.currentTimeMillis() + 999) / 1000;
    }

    /** Returns true if this record has not expired yet, and false otherwise. An expired record
     * should not be maintained in cache, and should instead be retrieved again from an
     * authoritative DNS server.
     *
     * @return true if this record has not expired yet, and false otherwise.
     */
    public boolean isStillValid() {
        return expirationTime.after(new Date());
    }

    /** Returns true if this record expires before another record. This method may be used to
     * identify if a newly acquired record should replace the one currently in the cache. It
     * may also potentially be used, for example, to identify if a CNAME record expires before
     * the equivalent A record it links to.
     *
     * @param record Another resource record whose expiration this record should be compared with.
     * @return true if this record expires before the parameter record, or false otherwise.
     */
    public boolean expiresBefore(ResourceRecord record) {
        return this.expirationTime.before(record.expirationTime);
    }

    public String getTextResult() {
        return textResult;
    }

    public int getRecordLength() {
        return recordLength;
    }

    public DNSNode getNode() {
        return node;
    }

    public String getHostName() {
        return node.getHostName();
    }

    public int getType() {
        return node.getType().getCode();
    }

    public void changeHostName(String newHostName){
        this.node.changeHostName(newHostName);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ResourceRecord record = (ResourceRecord) o;

        if (!node.equals(record.node)) return false;
        if (!textResult.equals(record.textResult)) return false;
        return inetResult != null ? inetResult.equals(record.inetResult) : record.inetResult == null;
    }

    @Override
    public int hashCode() {
        int result = node.hashCode();
        result = 31 * result + textResult.hashCode();
        return result;
    }

}
