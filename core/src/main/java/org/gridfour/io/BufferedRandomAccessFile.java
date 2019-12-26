/* --------------------------------------------------------------------
 *
 * The MIT License
 *
 * Copyright (C) 2019  Gary W. Lucas.

 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 * ---------------------------------------------------------------------
 */

 /*
 * -----------------------------------------------------------------------
 *
 * Revision History:
 * Date     Name         Description
 * ------   ---------    -------------------------------------------------
 * 10/2019  G. Lucas     Created  
 *
 * Notes:
 *
 * -----------------------------------------------------------------------
 */
package org.gridfour.io;

import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/**
 * Provides a wrapper around a Java Random Access File which supports buffered
 * I/O, dramatically improving the performance for many kinds of random-access
 * file read and write operations.
 * <p>
 * Many of the methods in this class are based on Java's API DataInput and
 * DataOutput interfaces.
 * <p>
 * <strong>Note: </strong> It is the intent of this implementation to ultimately
 * be expanded to implement the full Java DataInput and DataOutput interfaces.
 * Because Java defines these methods and accessing a file in big-endian byte
 * order, methods with names such as readInt and writeInt would necessarily have
 * to operate over data given in big-endian order. Thus those methods in the
 * current implementation that operate on multi-byte data types (int, long,
 * short, float, double) all begin with the prefix "le" for "little endian".
 *
 */
public class BufferedRandomAccessFile implements Closeable, AutoCloseable {

  private static final int BUFFER_SIZE = 8 * 1024;

  final File file;
  final ByteBuffer buffer
          = ByteBuffer.allocateDirect(BUFFER_SIZE).order(
                  ByteOrder.LITTLE_ENDIAN);
  RandomAccessFile raf;
  FileChannel rafChannel;

  /**
   * the length of the virtual file; may be longer than actual file length if
   * write buffer is not yet written
   */
  long virtualLength;
  /**
   * the position in the virtual file (not in the actual file)
   */
  long virtualPosition;

  long truePosition;

  boolean writeDataIsInBuffer;
  boolean readDataIsInBuffer;

  /**
   * Private constructor to discourage applications from creating an instance
   * using the default constructor.
   */
  private BufferedRandomAccessFile() {
    file = null;
  }

  /**
   * Opens the specified file for read or write access using the specified
   * access mode following the conventions of the Java RandomnAccessFile class.
   * Since the BufferedRandomAccessFile is a wrapper around the Java class,
   * application developers can expect this class to perform similar operations
   * to the Java class.
   *
   * @param fileName a valid path or file name for opening a file
   * @param mode the access mode following the conventions of Java
   * @throws IOException in the event of an I/O error
   */
  public BufferedRandomAccessFile(String fileName, String mode)
          throws IOException {
    if (fileName == null) {
      throw new NullPointerException();
    }
    if (fileName.length() == 0) {
      throw new IllegalArgumentException(
              "Null length file name not allowed");
    }

    file = new File(fileName);
    openFile(file, mode);
  }

  /**
   * Opens the specified file for read or write access using the specified
   * access mode following the conventions of the Java RandomnAccessFile class.
   * Since the BufferedRandomAccessFile is a wrapper around the Java class,
   * application developers can expect this class to perform similar operations
   * to the Java class.
   *
   * @param file a valid file reference
   * @param mode the access mode following the conventions of Java
   * @throws IOException in the event of an I/O error
   */
  public BufferedRandomAccessFile(File file, String mode) throws IOException {
    if (file == null) {
      throw new NullPointerException();
    }
    this.file = file;
    openFile(file, mode);
  }

  private void openFile(File file, String mode)
          throws FileNotFoundException, IOException {
    if (mode == null) {
      throw new NullPointerException();
    }
    raf = new RandomAccessFile(file, mode);
    rafChannel = raf.getChannel();
    virtualLength = raf.length();
    virtualPosition = 0;
    raf.seek(0);
    truePosition = 0;
    readDataIsInBuffer = false;
    writeDataIsInBuffer = false;
  }

  private void prepRead(int nBytesToRead) throws IOException {
    int nBytes = nBytesToRead;

    // Development note: 
    // For this to work, it is imperative that when writeDataIsInBuffer
    // is true, readDataInBuffer must be false.   Also, if the file has
    // been closed, both flags must be false.
    assert !readDataIsInBuffer || !writeDataIsInBuffer : "Read/Write conflict";
    if (readDataIsInBuffer) {
      //sufficient read data is already in the buffer 
      int remaining = buffer.remaining();
      if (remaining >= nBytesToRead) {
        virtualPosition += nBytesToRead;
        return;
      }

      // there is only a partial in the buffer, a read operation
      // will have to occur
      if (remaining == 0) {
        buffer.clear();
      } else {
        buffer.compact();
        // we have a partial in the buffer... so we need
        // to advance the virtual position ahead so that we don't re-read
        // what we've already pulled in.
        virtualPosition += remaining;
        nBytes -= remaining;
      }

    } else if (writeDataIsInBuffer) {
      flushWrite();  // flushWrite sets truePosition = -1, which means "unknown"
      // flushWrite clears the buffer and sets readDataIsInBuffer=false
    } else {
      // neither readDataIsInBuffer nor writeDataIsInBuffer was set.
      buffer.clear();
      if (raf == null) {
        throw new IOException("Reading from a file that was closed");
      }
    }

    // perform a read operation -------------------------------
    if (virtualPosition >= virtualLength) {
      throw new EOFException();
    }

    if (virtualPosition != truePosition) {
      rafChannel.position(virtualPosition);
      truePosition = virtualPosition;
    }

    int nBytesRead = rafChannel.read(buffer);
    if (nBytesRead < 0) {
      throw new EOFException();
    }
    buffer.flip();
    readDataIsInBuffer = true;
    truePosition += nBytesRead;
    virtualPosition += nBytes;
  }

  private void flushWrite() throws IOException {
    long filePos = virtualPosition - buffer.position();
    buffer.flip();
    rafChannel.write(buffer, filePos);
    buffer.clear();
    writeDataIsInBuffer = false;
    readDataIsInBuffer = false;
    truePosition = -1;
  }

  private void prepWrite(int nBytesToWrite) throws IOException {
    assert !readDataIsInBuffer || !writeDataIsInBuffer : "Write/Read conflict";
    if (raf == null) {
      throw new IOException("Writing to a file that was closed");
    }
    if (!writeDataIsInBuffer) {
      buffer.clear();
      readDataIsInBuffer = false;
    } else {
      int remaining = buffer.remaining();
      if (remaining < nBytesToWrite) {
        if (writeDataIsInBuffer) {
          flushWrite();
        }
        buffer.clear();
      }
    }
    writeDataIsInBuffer = true;
    virtualPosition += nBytesToWrite;
    if (virtualPosition > virtualLength) {
      virtualLength = virtualPosition;
    }
  }

  @Override
  public void close() throws IOException {
    if (raf != null) {
      if (this.writeDataIsInBuffer) {
        this.flushWrite();
      }
      raf.close();
    }

    // put internal elements out-of-scope to expedite garbage collection
    raf = null;
    rafChannel = null;
    buffer.clear();
  }

  /**
   * Indicates whether the file is closed.
   *
   * @return true if the file is closed; otherwise, true
   */
  public boolean isClosed() {
    return raf == null;
  }

  /**
   * Ensures that any pending output stored in the buffer is written to the
   * underlying random access file.
   *
   * @throws IOException if an I/O error occurs.
   */
  public void flush() throws IOException {
    if (raf != null) {
      if (this.writeDataIsInBuffer) {
        this.flushWrite();
      }
    }
  }

  public long getFileSize() {
    return virtualLength;
  }

  public long getFilePosition() {
    return virtualPosition;
  }

  public File getFile() {
    return file;
  }

  public double leReadDouble() throws IOException {
    prepRead(8);
    return buffer.getDouble();
  }

  public float leReadFloat() throws IOException {
    prepRead(4);
    return buffer.getFloat();
  }

  public int leReadInt() throws IOException {
    prepRead(4);
    return buffer.getInt();
  }

  public long leReadLong() throws IOException {
    prepRead(8);
    return buffer.getLong();
  }

  public short leReadShort() throws IOException {         //NOPMD
    prepRead(2);
    return buffer.getShort();
  }

  public int leReadUnsignedShort() throws IOException {
    prepRead(2);
    return buffer.getShort() & 0x0000ffff;
  }

  public void leWriteShort(int v) throws IOException {
    prepWrite(2);
    buffer.putShort((short) (v & 0xffff));      //NOPMD
  }

  public void leWriteInt(int value) throws IOException {
    prepWrite(4);
    buffer.putInt(value);
  }

  public void leWriteLong(long v) throws IOException {
    prepWrite(8);
    buffer.putLong(v);
  }

  public void leWriteFloat(float v) throws IOException {
    prepWrite(4);
    buffer.putFloat(v);
  }

  public void leWriteDouble(double v) throws IOException {
    prepWrite(8);
    buffer.putDouble(v);
  }

  public void leReadIntArray(int[] array, int arrayOffset, int length)
          throws IOException {
    int offset = arrayOffset;
    int nIntToRead = length;
    if (length <= 0) {
      return;
    }

    if (readDataIsInBuffer) {
      int remaining = buffer.remaining();
      int n = remaining / 4;
      if (n >= nIntToRead) {
        // The read can be fully satisfied by what's in the buffer
        buffer.asIntBuffer().get(array, offset, nIntToRead);
        buffer.position(buffer.position() + nIntToRead * 4);
        virtualPosition += nIntToRead * 4;
        return;
      } else {
        buffer.asIntBuffer().get(array, offset, n);
        buffer.position(buffer.position() + n * 4);
        offset += n;
        nIntToRead -= n;
        virtualPosition += n * 4;
        remaining -= n * 4;
        if (remaining == 0) {
          buffer.clear();
        } else {
          buffer.compact();
        }
      }
    } else {
      // read data was not in buffer
      if (writeDataIsInBuffer) {
        flushWrite();
      }
      if (virtualPosition != truePosition) {
        rafChannel.position(virtualPosition);
        truePosition = virtualPosition;
      }
      buffer.clear();
    }

    while (true) {
      if (virtualPosition >= virtualLength) {
        throw new EOFException();
      }

      int nBytesRead = rafChannel.read(buffer);
      if (nBytesRead < 0) {
        throw new EOFException();
      }
      truePosition += nBytesRead;
      buffer.flip();
      readDataIsInBuffer = true;
      truePosition += nBytesRead;

      int remaining = buffer.remaining();
      int n = remaining / 4;
      if (n >= nIntToRead) {
        buffer.asIntBuffer().get(array, offset, nIntToRead);
        buffer.position(buffer.position() + nIntToRead * 4);
        virtualPosition += nIntToRead * 4;
        return;
      }
      buffer.asIntBuffer().get(array, offset, n);
      buffer.position(buffer.position() + n * 4);
      offset += n;
      nIntToRead -= n;
      virtualPosition += n * 4;
      remaining -= n * 4;
      if (remaining == 0) {
        // because the size of the buffer is a multiple of 4
        // this will pretty much always be the case.
        buffer.clear();
      } else {
        buffer.compact();
      }

    }
  }

  public void leReadFloatArray(float[] array, int arrayOffset, int length)
          throws IOException {
    int offset = arrayOffset;
    int nIntToRead = length;
    if (length <= 0) {
      return;
    }

    if (readDataIsInBuffer) {
      int remaining = buffer.remaining();
      int n = remaining / 4;
      if (n >= nIntToRead) {
        // The read can be fully satisfied by what's in the buffer
        buffer.asFloatBuffer().get(array, offset, nIntToRead);
        buffer.position(buffer.position() + nIntToRead * 4);
        virtualPosition += nIntToRead * 4;
        return;
      } else {
        buffer.asFloatBuffer().get(array, offset, n);
        buffer.position(buffer.position() + n * 4);
        offset += n;
        nIntToRead -= n;
        virtualPosition += n * 4;
        remaining -= 4;
        if (remaining == 0) {
          buffer.clear();
        } else {
          buffer.compact();
        }
      }
    } else {
      // read data was not in buffer
      if (writeDataIsInBuffer) {
        flushWrite();
      }
      if (virtualPosition != truePosition) {
        rafChannel.position(virtualPosition);
        truePosition = virtualPosition;
      }
      buffer.clear();
    }

    while (true) {
      if (virtualPosition >= virtualLength) {
        throw new EOFException();
      }

      int nBytesRead = rafChannel.read(buffer);
      if (nBytesRead < 0) {
        throw new EOFException();
      }
      truePosition += nBytesRead;
      buffer.flip();
      readDataIsInBuffer = true;
      truePosition += nBytesRead;

      int remaining = buffer.remaining();
      int n = remaining / 4;
      if (n >= nIntToRead) {
        buffer.asFloatBuffer().get(array, offset, nIntToRead);
        buffer.position(buffer.position() + nIntToRead * 4);
        virtualPosition += nIntToRead * 4;
        return;
      }
      buffer.asFloatBuffer().get(array, offset, n);
      buffer.position(buffer.position() + n * 4);
      offset += n;
      nIntToRead -= n;
      virtualPosition += n * 4;
      remaining -= n * 4;
      if (remaining == 0) {
        // because the size of the buffer is a multiple of 4
        // this will pretty much always be the case.
        buffer.clear();
      } else {
        buffer.compact();
      }

    }
  }

  /**
   * Reads one input byte and returns true if that byte is nonzero, false if
   * that byte is zero. This method is suitable for reading the byte written by
   * the writeBoolean method of this class.
   *
   * @return the boolean value read from the source file.
   * @throws IOException if an I/O error occurs.
   */
  public boolean readBoolean() throws IOException {
    prepRead(1);
    byte test = buffer.get();
    return test != 0;
  }

  /**
   * Reads a single byte from the file, interpreting it as a signed value in the
   * range -128 through 127, inclusive. This method is suitable for reading the
   * byte written by the writeByte method of this class.
   *
   * @return a signed by value.
   * @throws IOException if an I/O error occurs.
   */
  public byte readByte() throws IOException {
    prepRead(1);
    return buffer.get();
  }

  /**
   * Reads a single byte from the file, interpreting it as an ASCII character.
   *
   * @return a character in the range 0 to 255.
   * @throws IOException if an I/O error occurs.
   */
  public char readCharASCII() throws IOException {
    return (char) readUnsignedByte();
  }

  /**
   * Reads enough bytes from the input file to fill the specified byte array.
   * Bytes are signed values in the range -128 to 127.
   *
   * @param array a valid array
   * @throws IOException if an I/O error occurs, including an end-of-file
   * condition.
   */
  public void readFully(byte[] array) throws IOException {
    readFully(array, 0, array.length);
  }

  /**
   * Reads the specified number of bytes from the input file into the provided
   * array starting at the specified offset position.Bytes are signed values in
   * the range -128 to 127.
   *
   * @param array a valid array
   * @param arrayOffset the starting index in the array
   * @param length the number of bytes to read
   * @throws IOException if an I/O error occurs, including an end-of-file
   * condition.
   */
  public void readFully(byte[] array, int arrayOffset, int length)
          throws IOException {
    int offset = arrayOffset;
    int nByteToRead = length;
    if (length <= 0) {
      return;
    }

    if (readDataIsInBuffer) {
      int remaining = buffer.remaining();
      int n = remaining;
      if (n >= nByteToRead) {
        // The read can be fully satisfied by what's in the buffer
        buffer.get(array, offset, nByteToRead);
        virtualPosition += nByteToRead;
        return;
      } else {
        buffer.get(array, offset, n);
        offset += n;
        nByteToRead -= n;
        virtualPosition += n;
        remaining -= n;
        if (remaining == 0) {
          buffer.clear();
        } else {
          buffer.compact();
        }
      }
    } else {
      // read data was not in buffer
      if (writeDataIsInBuffer) {
        flushWrite();
      }
      if (virtualPosition != truePosition) {
        rafChannel.position(virtualPosition);
        truePosition = virtualPosition;
      }
      buffer.clear();
    }

    while (true) {
      if (virtualPosition >= virtualLength) {
        throw new EOFException();
      }

      int nBytesRead = rafChannel.read(buffer);
      if (nBytesRead < 0) {
        throw new EOFException();
      }
      truePosition += nBytesRead;
      buffer.flip();
      readDataIsInBuffer = true;
      truePosition += nBytesRead;

      int remaining = buffer.remaining();
      int n = remaining;
      if (n >= nByteToRead) {
        buffer.get(array, offset, nByteToRead);
        virtualPosition += nByteToRead;
        return;
      }
      buffer.get(array, offset, n);
      offset += n;
      nByteToRead -= n;
      virtualPosition += n;
      remaining -= n;
      if (remaining == 0) {
        buffer.clear();
      } else {
        buffer.compact();
      }
    }
  }

  /**
   * Reads a single unsigned byte from the file, interpreting it as a signed
   * value in the range 0 through 255, inclusive. This method is suitable for
   * reading the byte written by the writeByte method of this class.
   *
   * @return a signed by value.
   * @throws IOException if an I/O error occurs.
   */
  public int readUnsignedByte() throws IOException {
    prepRead(1);
    int i = (int) (buffer.get()) & 0x000000ff;
    return i;
  }

  /**
   * Reads the specified number of bytes from the source file treating them as
   * ASCII characters and appending them to a string builder. If a zero byte is
   * detected, it is treated as a null terminator and the additional bytes will
   * not be appended to the builder, but the file position will be advanced by
   * the specified number of bytes.
   *
   * @param builder a valid StringBuilder instance, not necessarily empty.
   * @param nBytesToRead the number of bytes to read from the file
   * @return the length of the null-terminated string read from the file (not
   * the length of the StringBuilder content).
   * @throws IOException if an I/O error occurs.
   */
  public int readASCII(StringBuilder builder, int nBytesToRead)
          throws IOException {
    prepRead(nBytesToRead);
    int b;
    char c;
    int nValid = 0, nRead = 0;
    while (nRead < nBytesToRead) {
      b = buffer.get();
      nRead++;
      if (b == 0) {
        break;
      }
      if (b < 0) {
        b += 256;
      }
      nValid = nRead;
      c = (char) b;
      builder.append(c);
    }

    while (nRead < nBytesToRead) {
      b = buffer.get();
      nRead++;
    }
    return nValid;
  }

  /**
   * Reads the specified number of bytes from the source file treating them as
   * ASCII characters and returning a valid, potentially zero-length string. If
   * a zero byte is detected in the input, it is treated as a null terminator
   * and the additional bytes will not be appended to the string, but the file
   * position will be advanced by the specified number of bytes.
   *
   * @param nBytesToRead the number of bytes to read from the file
   * @return the length of the null-terminated string read from the file (not
   * the length of the StringBuilder content).
   * @throws IOException if an I/O error occurs.
   */
  public String readASCII(int nBytesToRead) throws IOException {
    if (nBytesToRead <= 0) {
      return "";
    }
    StringBuilder builder = new StringBuilder(nBytesToRead);
    readASCII(builder, nBytesToRead);
    return builder.toString();
  }

  /**
   * Sets the file position given as an offset the beginning of the file. This
   * setting gives the position at which the next read or write will occur.
   *
   * @param position the file-pointer offset position
   * @throws IOException if the position is less than zero or an I/O error
   * occurs.
   */
  public void seek(long position) throws IOException {
    if (writeDataIsInBuffer) {
      flushWrite();
    } else if (readDataIsInBuffer) {
      int bufferPosition = buffer.position();
      int bufferRemaining = buffer.remaining();
      long pos0 = virtualPosition - bufferPosition;
      long pos1 = virtualPosition + bufferRemaining - 1;
      if (pos0 <= position && position <= pos1) {
        virtualPosition = position;
        long bufferPos = position - pos0;
        buffer.position((int) bufferPos);
        return;
      }
    }

    readDataIsInBuffer = false;
    buffer.clear();
    virtualPosition = position;
    truePosition = position;
    rafChannel.position(position);
  }

  /**
   * Attempts to skip over n bytes.
   *
   * @param n the number of bytes to skip
   * @return the actual number of bytes skipped.
   * @throws IOException if an I/O error occurs.
   */
  public int skipBytes(int n) throws IOException {
    // handle the most straightforward case directly,
    // otherwise, fall through to seek().
    if (readDataIsInBuffer && buffer.remaining() > n) {
      int position = buffer.position();
      buffer.position(position + n);
      virtualPosition += n;
      return n;
    } else {
      seek(virtualPosition + n);
    }
    return n;
  }

  public void writeUnsignedByte(int value) throws IOException {
    prepWrite(1);
    buffer.put((byte) (value & 0xff));
  }

  public void writeFully(byte[] array) throws IOException {
    writeFully(array, 0, array.length);
  }

  public void writeFully(byte[] array, int arrayOffset, int length)
          throws IOException {
    assert !readDataIsInBuffer || !writeDataIsInBuffer : "Write/Read conflict";
    int offset = arrayOffset;

    if (length == 0) {
      return;
    }

    if (array == null) {
      throw new NullPointerException();
    }

    if (array.length < offset + length) {
      throw new IllegalArgumentException("Input array is too small");
    }

    if (raf == null) {
      throw new IOException("Reading from a file that was closed");
    }
    if (readDataIsInBuffer) {
      buffer.clear();
      readDataIsInBuffer = false;
    }

    writeDataIsInBuffer = true;
    int needed = length;
    int remaining, nCopy;
    while (needed > 0) {
      remaining = buffer.remaining();
      if (remaining == 0) {
        int pos = buffer.position();
        buffer.flip();
        rafChannel.write(buffer, virtualPosition - pos);
        buffer.clear();
        remaining = buffer.remaining();
        truePosition = -1;
      }
      nCopy = needed;
      if (nCopy > remaining) {
        nCopy = remaining;
      }
      buffer.put(array, offset, nCopy);
      needed -= nCopy;
      offset += nCopy;
      virtualPosition += nCopy;
      if (virtualPosition > virtualLength) {
        virtualLength = virtualPosition;
      }
    }
  }

  public void write(int b) throws IOException {
    prepWrite(1);
    buffer.put((byte) (b & 0xff));
  }

  /**
   * Writes an array of bytes to the output
   *
   * @param b a valid array of bytes
   * @throws IOException in the event of an unexpected I/O condition
   */
  public void write(byte[] b) throws IOException {
    writeFully(b, 0, b.length);
  }

  public void write(byte[] b, int offset, int length) throws IOException {
    writeFully(b, offset, length);
  }

  public void writeBoolean(boolean v) throws IOException {
    prepWrite(1);
    if (v) {
      buffer.put((byte) 1);
    } else {
      buffer.put((byte) 0);
    }
  }

  public void writeByte(int v) throws IOException {
    prepWrite(1);
    buffer.put((byte) (v & 0xff));
  }

  public void writeBytes(String s) throws IOException {
    if (s == null) {
      throw new NullPointerException();
    }
    if (s.length() == 0) {
      return;
    }
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      int x = c;
      writeByte(x);
    }
  }

  public void writeASCII(String s, int nBytes) throws IOException {
    if (s == null) {
      throw new NullPointerException();
    }
    int n = s.length();
    if (n > nBytes) {
      n = nBytes;
    }
    byte b[] = new byte[nBytes];
    for (int i = 0; i < n; i++) {
      char c = s.charAt(i);
      int x = c;
      b[i] = (byte) (x & 0xff);
    }
    this.write(b);
  }

  /**
   * Reads a 4-byte integer value in the big-endian order compatible with the
   * Java DataInputStream class.
   *
   * @return if successful, a valid integer value
   * @throws IOException in the event of an I/O error
   */
  public int readInt() throws IOException {
    this.prepRead(4);
    buffer.order(ByteOrder.BIG_ENDIAN);
    int test = buffer.getInt();
    buffer.order(ByteOrder.LITTLE_ENDIAN);
    return test;
  }

  /**
   * Prints current state data for file. Intended for debugging and development.
   *
   * @param ps a valid PrintStream for writing the output (may be
   * System&#46;out).
   */
  public void printDiagnostics(PrintStream ps) {
    long position = 0;
    long length = 0;
    if (raf == null) {
      ps.println("File is closed");
    } else {
      try {
        position = rafChannel.position();
        length = raf.length();
      } catch (IOException ioex) {
        ps.println("I/O Exception accessing file " + ioex.getMessage());
      }
    }
    ps.format("Virtual Length:      %12d%n", virtualLength);
    ps.format("Virtual Position:    %12d%n", virtualPosition);
    ps.format("RAF Channel Position:%12d%n", truePosition);
    ps.format("Actual Position:     %12d%n", position);
    ps.format("Actual Length:       %12d%n", length);
    ps.format("Buffer position:     %12d%n", buffer.position());
    ps.format("Buffer remainder     %12d%n", buffer.remaining());
    ps.format("Write data buffered: %12s%n", writeDataIsInBuffer ? "true" : "false");
    ps.format("Read data buffered:  %12s%n", readDataIsInBuffer ? "true" : "false");
  }

}
