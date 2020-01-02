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
 * 12/2019  G. Lucas     Refactored from earlier inner classes
 *                         so that it could be used generically.
 *
 * Notes:
 *
 * -----------------------------------------------------------------------
 */
package org.gridfour.g93;

/**
 * A simple container for collecting statistics when analyzing compressed data.
 * <p>
 * This statistics collector is suited to the standard compressors implemented in
 * G93, but may not be applicable to other compressors, particularly those
 * implemented by a third party.  This class is provided as a convenience
 * for developers who which to implement their own compressors.
 */
public class CodecStats {

  final PredictorCorrectorType pcType;
  long nTilesCounted;
  long nBytesTotal;
  long nSymbolsTotal;
  long nBitsOverheadTotal;
  long[] mCount = new long[256];

  /**
   * Construct a statistics object for the specified predictor-corrector.
   * @param pcType a valid predictor-corrector type.
   */
  public CodecStats(PredictorCorrectorType pcType) {
    this.pcType = pcType;
  }

  /**
   * Get a label for the predictor-corrector.
   * @return a valid string
   */
  String getLabel() {
    return pcType.name();
  }

  /**
   * Add metadata about the tile to the counts
   * @param nBytesForTile the number of byts for the compressed version of the
   * tile.
   * @param nSymbolsInTile the number of values in the tile, typically
   * the number of cells in the grid.
   * @param nBitsOverhead Any compressor-specific overhead that an
   * implementation wishes to track.
   */
  void addToCounts(int nBytesForTile, int nSymbolsInTile, int nBitsOverhead) {
    nTilesCounted++;
    nBytesTotal += nBytesForTile;
    nSymbolsTotal += nSymbolsInTile;
    nBitsOverheadTotal += nBitsOverhead;
  }

  /**
   * Add counts for the M32 symbols derived from the predictor-correctors
   * for the tile.
   * @param nM32 the total number of symbols in the encoded data
   * @param m32 the encoded data
   */
  void addCountsForM32(int nM32, byte[] m32) {
    for (int i = 0; i < nM32; i++) {
      mCount[m32[i] & 0xff]++;
    }
  }

  private static final double log2 = Math.log(2.0);

  /**
   * Get the entropy for the data. In information theory, the term 
   * "entropy" is essentially an indicator of the number of bits actually
   * neeeed to encode data.
   * @return a positive value
   */
  double getEntropy() {
    long total = 0;
    for (int i = 0; i < 256; i++) {
      total += mCount[i];
    }
    if (total == 0) {
      return 0;
    }
    double d = (double) total;
    double s = 0;
    for (int i = 0; i < 256; i++) {
      if (mCount[i] > 0) {
        double p = mCount[i] / d;
        s += p * Math.log(p) / log2;
      }
    }
    return -s;
  }

  /**
   * Clear any accumulated counts
   */
  void clear() {
    nTilesCounted = 0;
    nBytesTotal = 0;
    nSymbolsTotal = 0;
    nBitsOverheadTotal = 0;
  }

  /**
   * Get the number of bits per symbol in the pre-compression encoding.
   * @return zero or a positive number
   */
  double getBitsPerSymbol() {
    if (nSymbolsTotal == 0) {
      return 0;
    }
    return 8.0 * (double) nBytesTotal / (double) nSymbolsTotal;
  }

  /**
   * Get the number of tiles that have been counted.
   * @return zero or a positive number
   */
  long getTileCount() {
    return nTilesCounted;
  }

  /**
   * Get the average overhead per tile. Not all compressors tabulate
   * this value.
   * @return zero or a positive value.
   */
  double getAverageOverhead() {
    if (nTilesCounted == 0) {
      return 0;
    }
    return (double) nBitsOverheadTotal / (double) nTilesCounted;
  }

  /**
   * Get the average length of encoded tiles, in bytes.
   * @return zero or a positive number.
   */
  double getAverageLength() {
    if (nTilesCounted == 0) {
      return 0;
    }
    return (double) nBytesTotal / (double) nTilesCounted;
  }
}
