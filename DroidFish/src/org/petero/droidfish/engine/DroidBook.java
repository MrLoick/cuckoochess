/*
    DroidFish - An Android chess program.
    Copyright (C) 2011  Peter Österlund, peterosterlund2@gmail.com

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package org.petero.droidfish.engine;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.petero.droidfish.gamelogic.ChessParseError;
import org.petero.droidfish.gamelogic.Move;
import org.petero.droidfish.gamelogic.MoveGen;
import org.petero.droidfish.gamelogic.Position;
import org.petero.droidfish.gamelogic.TextIO;
import org.petero.droidfish.gamelogic.Pair;
import org.petero.droidfish.gamelogic.UndoInfo;

import chess.Piece;

/**
 * Implements an opening book.
 * @author petero
 */
public final class DroidBook {
    static class BookEntry {
        Move move;
        int count;
        BookEntry(Move move) {
            this.move = move;
            count = 1;
        }
        @Override
        public String toString() {
            return TextIO.moveToUCIString(move) + " (" + count + ")";
        }
    }
    private static Map<Long, List<BookEntry>> bookMap;
    private static Random rndGen;
    private static int numBookMoves = -1;
    
    static PolyglotBook externalBook = null;

    public DroidBook() {
        if (externalBook == null)
            externalBook = new PolyglotBook(); 
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                initInternalBook();
            }
        });
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    public final void setBookFileName(String bookFileName) {
        externalBook.setBookFileName(bookFileName);
    }
    
    private synchronized final void initInternalBook() {
        if (numBookMoves >= 0)
            return;
        long t0 = System.currentTimeMillis();
        bookMap = new HashMap<Long, List<BookEntry>>();
        rndGen = new SecureRandom();
        rndGen.setSeed(System.currentTimeMillis());
        numBookMoves = 0;
        try {
            InputStream inStream = getClass().getResourceAsStream("/book.bin");
            List<Byte> buf = new ArrayList<Byte>(8192);
            byte[] tmpBuf = new byte[1024];
            while (true) {
                int len = inStream.read(tmpBuf);
                if (len <= 0) break;
                for (int i = 0; i < len; i++)
                    buf.add(tmpBuf[i]);
            }
            inStream.close();
            Position startPos = TextIO.readFEN(TextIO.startPosFEN);
            Position pos = new Position(startPos);
            UndoInfo ui = new UndoInfo();
            int len = buf.size();
            for (int i = 0; i < len; i += 2) {
                int b0 = buf.get(i); if (b0 < 0) b0 += 256;
                int b1 = buf.get(i+1); if (b1 < 0) b1 += 256;
                int move = (b0 << 8) + b1;
                if (move == 0) {
                    pos = new Position(startPos);
                } else {
                    boolean bad = ((move >> 15) & 1) != 0;
                    int prom = (move >> 12) & 7;
                    Move m = new Move(move & 63, (move >> 6) & 63,
                                      promToPiece(prom, pos.whiteMove));
                    if (!bad)
                        addToBook(pos, m);
                    pos.makeMove(m, ui);
                }
            }
        } catch (ChessParseError ex) {
            throw new RuntimeException();
        } catch (IOException ex) {
            System.out.println("Can't read opening book resource");
            throw new RuntimeException();
        }
        {
            long t1 = System.currentTimeMillis();
            System.out.printf("Book moves:%d (parse time:%.3f)%n", numBookMoves,
                    (t1 - t0) / 1000.0);
        }
    }

    /** Add a move to a position in the opening book. */
    private final void addToBook(Position pos, Move moveToAdd) {
        List<BookEntry> ent = bookMap.get(pos.zobristHash());
        if (ent == null) {
            ent = new ArrayList<BookEntry>();
            bookMap.put(pos.zobristHash(), ent);
        }
        for (int i = 0; i < ent.size(); i++) {
            BookEntry be = ent.get(i);
            if (be.move.equals(moveToAdd)) {
                be.count++;
                return;
            }
        }
        BookEntry be = new BookEntry(moveToAdd);
        ent.add(be);
        numBookMoves++;
    }

    /** Return a random book move for a position, or null if out of book. */
    public final Move getBookMove(Position pos) {
        List<BookEntry> bookMoves = getBookEntries(pos);
        if (bookMoves == null) {
            return null;
        }

        ArrayList<Move> legalMoves = new MoveGen().pseudoLegalMoves(pos);
        legalMoves = MoveGen.removeIllegal(pos, legalMoves);
        int sum = 0;
        for (int i = 0; i < bookMoves.size(); i++) {
            BookEntry be = bookMoves.get(i);
            if (!legalMoves.contains(be.move)) {
                // If an illegal move was found, it means there was a hash collision,
                // or a corrupt external book file.
                return null;
            }
            sum += getWeight(bookMoves.get(i).count);
        }
        if (sum <= 0) {
            return null;
        }
        int rnd = rndGen.nextInt(sum);
        sum = 0;
        for (int i = 0; i < bookMoves.size(); i++) {
            sum += getWeight(bookMoves.get(i).count);
            if (rnd < sum) {
                return bookMoves.get(i).move;
            }
        }
        // Should never get here
        throw new RuntimeException();
    }

    final private int getWeight(int count) {
        if (externalBook.enabled()) {
            return count;
        } else {
            return (int)(Math.sqrt(count) * 100 + 1);
        }
    }

    /** Return a string describing all book moves. */
    public final Pair<String,ArrayList<Move>> getAllBookMoves(Position pos) {
        StringBuilder ret = new StringBuilder();
        ArrayList<Move> bookMoveList = new ArrayList<Move>();
        List<BookEntry> bookMoves = getBookEntries(pos);
        if (bookMoves != null) {
            Collections.sort(bookMoves, new Comparator<BookEntry>() {
                public int compare(BookEntry arg0, BookEntry arg1) {
                    if (arg1.count != arg0.count)
                        return arg1.count - arg0.count;
                    String str0 = TextIO.moveToUCIString(arg0.move);
                    String str1 = TextIO.moveToUCIString(arg1.move);
                    return str0.compareTo(str1);
                }});
            int totalCount = 0;
            for (BookEntry be : bookMoves)
                totalCount += be.count;
            if (totalCount <= 0) totalCount = 1;
            for (BookEntry be : bookMoves) {
                Move m = be.move;
                bookMoveList.add(m);
                String moveStr = TextIO.moveToString(pos, m, false);
                ret.append(moveStr);
                ret.append(':');
                int percent = (be.count * 100 + totalCount / 2) / totalCount;
                ret.append(percent);
                ret.append(' ');
            }
        }
        return new Pair<String, ArrayList<Move>>(ret.toString(), bookMoveList);
    }

    /** Creates the book.bin file. */
    public static void main(String[] args) throws IOException {
        List<Byte> binBook = createBinBook();
        FileOutputStream out = new FileOutputStream("../src/book.bin");
        int bookLen = binBook.size();
        byte[] binBookA = new byte[bookLen];
        for (int i = 0; i < bookLen; i++)
            binBookA[i] = binBook.get(i);
        out.write(binBookA);
        out.close();
    }
    
    public static List<Byte> createBinBook() {
        List<Byte> binBook = new ArrayList<Byte>(0);
        try {
            InputStream inStream = new Object().getClass().getResourceAsStream("/book.txt");
            InputStreamReader inFile = new InputStreamReader(inStream);
            BufferedReader inBuf = new BufferedReader(inFile, 8192);
            LineNumberReader lnr = new LineNumberReader(inBuf);
            String line;
            while ((line = lnr.readLine()) != null) {
                if (line.startsWith("#") || (line.length() == 0)) {
                    continue;
                }
                if (!addBookLine(line, binBook)) {
                    System.out.printf("Book parse error, line:%d\n", lnr.getLineNumber());
                    throw new RuntimeException();
                }
//              System.out.printf("no:%d line:%s%n", lnr.getLineNumber(), line);
            }
            lnr.close();
        } catch (ChessParseError ex) {
            throw new RuntimeException();
        } catch (IOException ex) {
            System.out.println("Can't read opening book resource");
            throw new RuntimeException();
        }
        return binBook;
    }

    /** Add a sequence of moves, starting from the initial position, to the binary opening book. */
    private static boolean addBookLine(String line, List<Byte> binBook) throws ChessParseError {
        Position pos = TextIO.readFEN(TextIO.startPosFEN);
        UndoInfo ui = new UndoInfo();
        String[] strMoves = line.split(" ");
        for (String strMove : strMoves) {
//            System.out.printf("Adding move:%s\n", strMove);
            int bad = 0;
            if (strMove.endsWith("?")) {
                strMove = strMove.substring(0, strMove.length() - 1);
                bad = 1;
            }
            Move m = TextIO.stringToMove(pos, strMove);
            if (m == null) {
                return false;
            }
            int prom = pieceToProm(m.promoteTo);
            int val = m.from + (m.to << 6) + (prom << 12) + (bad << 15);
            binBook.add((byte)(val >> 8));
            binBook.add((byte)(val & 255));
            pos.makeMove(m, ui);
        }
        binBook.add((byte)0);
        binBook.add((byte)0);
        return true;
    }

    private final List<BookEntry> getBookEntries(Position pos) {
        if (externalBook.enabled()) {
            return externalBook.getBookEntries(pos);
        } else {
            initInternalBook();
            return bookMap.get(pos.zobristHash());
        }
    }

    private static int pieceToProm(int p) {
        switch (p) {
        case Piece.WQUEEN: case Piece.BQUEEN:
            return 1;
        case Piece.WROOK: case Piece.BROOK:
            return 2;
        case Piece.WBISHOP: case Piece.BBISHOP:
            return 3;
        case Piece.WKNIGHT: case Piece.BKNIGHT:
            return 4;
        default:
            return 0;
        }
    }

    private static int promToPiece(int prom, boolean whiteMove) {
        switch (prom) {
        case 1: return whiteMove ? Piece.WQUEEN : Piece.BQUEEN;
        case 2: return whiteMove ? Piece.WROOK  : Piece.BROOK;
        case 3: return whiteMove ? Piece.WBISHOP : Piece.BBISHOP;
        case 4: return whiteMove ? Piece.WKNIGHT : Piece.BKNIGHT;
        default: return Piece.EMPTY;
        }
    }
}