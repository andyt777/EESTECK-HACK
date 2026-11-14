package ro.filegrid.storage.ftp;

import java.io.IOException;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import ro.filegrid.storage.StorageProperties;

/** Creates logged-in FTP connections (binary, passive mode) for the connection pool. */
class FtpClientFactory extends BasePooledObjectFactory<FTPClient> {

    private static final int TRANSFER_BUFFER_BYTES = 1024 * 1024;

    private final StorageProperties.Ftp ftp;

    FtpClientFactory(StorageProperties.Ftp ftp) {
        this.ftp = ftp;
    }

    @Override
    public FTPClient create() throws IOException {
        FTPClient client = new FTPClient();
        int connectTimeoutMs = Math.toIntExact(ftp.connectTimeout().toMillis());
        client.setConnectTimeout(connectTimeoutMs);
        client.setDefaultTimeout(connectTimeoutMs);
        client.setDataTimeout(ftp.dataTimeout());
        client.setBufferSize(TRANSFER_BUFFER_BYTES);
        try {
            client.connect(ftp.host(), ftp.port());
            if (!FTPReply.isPositiveCompletion(client.getReplyCode())) {
                throw new IOException("FTP server refused the connection: " + client.getReplyString().trim());
            }
            if (!client.login(ftp.username(), ftp.password())) {
                throw new IOException("FTP login failed for the service user");
            }
            client.setSoTimeout(Math.toIntExact(ftp.dataTimeout().toMillis()));
            if (!client.setFileType(FTP.BINARY_FILE_TYPE)) {
                throw new IOException("FTP server refused binary mode: " + client.getReplyString().trim());
            }
            client.enterLocalPassiveMode();
            return client;
        } catch (IOException e) {
            disconnectQuietly(client);
            throw e;
        }
    }

    @Override
    public PooledObject<FTPClient> wrap(FTPClient client) {
        return new DefaultPooledObject<>(client);
    }

    @Override
    public boolean validateObject(PooledObject<FTPClient> pooled) {
        try {
            return pooled.getObject().isConnected() && pooled.getObject().sendNoOp();
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void destroyObject(PooledObject<FTPClient> pooled) {
        disconnectQuietly(pooled.getObject());
    }

    private static void disconnectQuietly(FTPClient client) {
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
        } catch (IOException ignored) {
            // The connection is being discarded anyway.
        }
    }
}
