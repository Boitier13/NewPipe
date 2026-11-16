package us.shandian.giga.io;

import org.schabi.newpipe.streams.io.SharpStream;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * RandomAccessFile-backed SharpStream with a 256 KiB write buffer that flushes before any
 * seek, rewind, read, length, setLength, skip, or close operation so random-access MP4/WebM
 * headers remain exact while cutting tiny OS write syscalls by two orders of magnitude.
 *
 * @author kapodamy
 */
public class FileStream extends SharpStream {
    private static final int WRITE_BUFFER_SIZE = 256 * 1024;

    public RandomAccessFile source;
    private byte[] writeBuffer;
    private int writeBufferPos = 0;

    public FileStream(File target) throws FileNotFoundException {
        this.source = new RandomAccessFile(target, "rw");
    }

    public FileStream(String path) throws FileNotFoundException {
        this.source = new RandomAccessFile(path, "rw");
    }

    private void flushWriteBuffer() throws IOException {
        if (source != null && writeBufferPos > 0) {
            source.write(writeBuffer, 0, writeBufferPos);
            writeBufferPos = 0;
        }
    }

    @Override
    public int read() throws IOException {
        flushWriteBuffer();
        return source.read();
    }

    @Override
    public int read(byte[] b) throws IOException {
        flushWriteBuffer();
        return source.read(b);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        flushWriteBuffer();
        return source.read(b, off, len);
    }

    @Override
    public long skip(long pos) throws IOException {
        flushWriteBuffer();
        if (pos <= 0) {
            return 0;
        }
        long current = source.getFilePointer();
        long totalLength = source.length();
        if (current >= totalLength) {
            return 0;
        }
        long skipped = Math.min(pos, totalLength - current);
        source.seek(current + skipped);
        return skipped;
    }

    @Override
    public long available() {
        if (source == null) {
            return 0;
        }
        try {
            flushWriteBuffer();
            return Math.max(0L, source.length() - source.getFilePointer());
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public void flush() throws IOException {
        flushWriteBuffer();
    }

    @Override
    public void close() {
        if (source == null) return;
        try {
            flushWriteBuffer();
        } catch (IOException err) {
            // nothing to do
        }
        try {
            source.close();
        } catch (IOException err) {
            // nothing to do
        }
        source = null;
        writeBuffer = null;
        writeBufferPos = 0;
    }

    @Override
    public boolean isClosed() {
        return source == null;
    }

    @Override
    public void rewind() throws IOException {
        flushWriteBuffer();
        source.seek(0);
    }

    @Override
    public boolean canRewind() {
        return true;
    }

    @Override
    public boolean canRead() {
        return true;
    }

    @Override
    public boolean canWrite() {
        return true;
    }

    @Override
    public boolean canSeek() {
        return true;
    }

    @Override
    public boolean canSetLength() {
        return true;
    }

    @Override
    public void write(byte value) throws IOException {
        if (writeBuffer == null) {
            writeBuffer = new byte[WRITE_BUFFER_SIZE];
        }
        if (writeBufferPos >= writeBuffer.length) {
            flushWriteBuffer();
        }
        writeBuffer[writeBufferPos++] = value;
    }

    @Override
    public void write(byte[] buffer) throws IOException {
        write(buffer, 0, buffer.length);
    }

    @Override
    public void write(byte[] buffer, int offset, int count) throws IOException {
        if (count <= 0) {
            return;
        }
        if (writeBuffer == null) {
            writeBuffer = new byte[WRITE_BUFFER_SIZE];
        }
        if (count >= writeBuffer.length) {
            flushWriteBuffer();
            source.write(buffer, offset, count);
            return;
        }
        if (writeBufferPos + count > writeBuffer.length) {
            flushWriteBuffer();
        }
        System.arraycopy(buffer, offset, writeBuffer, writeBufferPos, count);
        writeBufferPos += count;
    }

    @Override
    public void setLength(long length) throws IOException {
        flushWriteBuffer();
        source.setLength(length);
    }

    @Override
    public void seek(long offset) throws IOException {
        flushWriteBuffer();
        source.seek(offset);
    }

    @Override
    public long length() throws IOException {
        flushWriteBuffer();
        return source.length();
    }
}
