package com.yahoo.glimmer.util;

/*
 * Copyright (c) 2012 Yahoo! Inc. All rights reserved.
 * 
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is 
 *  distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and limitations under the License.
 *  See accompanying LICENSE file.
 */

import it.unimi.dsi.big.util.ShiftAddXorSignedStringMap;
import it.unimi.dsi.bits.TransformationStrategies;
import it.unimi.dsi.fastutil.objects.AbstractObject2LongFunction;
import it.unimi.dsi.io.FastBufferedReader;
import it.unimi.dsi.io.SafelyCloseable;
import it.unimi.dsi.lang.MutableString;
import it.unimi.dsi.sux4j.mph.LcpMonotoneMinimalPerfectHashFunction;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.SequenceInputStream;
import java.nio.charset.Charset;
import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.log4j.Logger;

import com.martiansoftware.jsap.FlaggedOption;
import com.martiansoftware.jsap.JSAP;
import com.martiansoftware.jsap.JSAPResult;
import com.martiansoftware.jsap.Parameter;
import com.martiansoftware.jsap.SimpleJSAP;
import com.martiansoftware.jsap.Switch;
import com.martiansoftware.jsap.UnflaggedOption;
import com.martiansoftware.jsap.stringparsers.ForNameStringParser;
import com.martiansoftware.jsap.stringparsers.IntegerStringParser;

public class ComputeHashTool extends Configured implements Tool {
    private final static Logger LOGGER = Logger.getLogger(ComputeHashTool.class);
    private static final String SRC_FILES_ARG = "srcFilenames";
    private static final String SIGNED_ARG = "signed";
    private static final String UNSIGNED_ARG = "unsigned";
    private static final String WRITE_INFO_ARG = "info";
    private static final String SIGNATURE_WIDTH_ARG = "signatureWidth";
    private static final String FILE_ENCODING_ARG = "encoding";
    public static final FsPermission ALL_PERMISSIONS = new FsPermission(FsAction.ALL, FsAction.ALL, FsAction.ALL);
    private static final String DOT_UNSIGNED = ".map";
    private static final String DOT_SIGNED = ".smap";
    private static final String DOT_MAPINFO = ".mapinfo";

    @Override
    public int run(String[] args) throws Exception {
	final SimpleJSAP jsap = new SimpleJSAP(ComputeHashTool.class.getName(), "Builds a hash function.", new Parameter[] {
		new Switch(SIGNED_ARG, 's', SIGNED_ARG, "Generate signed hashes."),
		new Switch(UNSIGNED_ARG, 'u', UNSIGNED_ARG, "Generate unsiged hashes."),
		new Switch(WRITE_INFO_ARG, 'i', WRITE_INFO_ARG, "Write a .info tab seperated text file with size/width info in."),
		new FlaggedOption(SIGNATURE_WIDTH_ARG, IntegerStringParser.getParser(), "32", JSAP.NOT_REQUIRED, 'w', "width",
			"Sign the hash with a hash width of w bits."),
		new FlaggedOption(FILE_ENCODING_ARG, ForNameStringParser.getParser(Charset.class), "UTF-8", JSAP.NOT_REQUIRED, 'e', "encoding",
			"Set the input file encoding(default is UTF-8)."),
		new UnflaggedOption(SRC_FILES_ARG, JSAP.STRING_PARSER, JSAP.NO_DEFAULT, JSAP.REQUIRED, JSAP.GREEDY,
			"The filenames (or HDFS dirs if building hashes) to work with.") });

	JSAPResult jsapResult = jsap.parse(args);
	if (jsap.messagePrinted()) {
	    throw new IllegalArgumentException("");
	}

	String[] srcFilenames = jsapResult.getStringArray(SRC_FILES_ARG);
	Charset srcFileCharset = (Charset) jsapResult.getObject(FILE_ENCODING_ARG);
	int signatureWidth = 0;
	boolean generateUnsigned = true;
	if (jsapResult.getBoolean(SIGNED_ARG)) {
	    signatureWidth = jsapResult.getInt(SIGNATURE_WIDTH_ARG);

	    if (jsapResult.getBoolean(UNSIGNED_ARG)) {
		LOGGER.info("Building unsigned and signed hashes with signature width of " + signatureWidth + " bits for " + srcFileCharset.displayName()
			+ " files:" + Arrays.toString(srcFilenames));
	    } else {
		LOGGER.info("Building signed hashes with signature width of " + signatureWidth + " bits for " + srcFileCharset.displayName() + " files:"
			+ Arrays.toString(srcFilenames));
		generateUnsigned = false;
	    }
	} else {
	    LOGGER.info("Building unsigned hashes for " + srcFileCharset.displayName() + " files:" + srcFilenames);
	}
	Configuration conf = getConf();
	//conf.set("fs.default.name","hdfs://127.0.0.1:9000/");
	JobConf job = new JobConf(conf, ComputeHashTool.class);
	FileSystem fs = FileSystem.get(job);
	for (String srcFilename : srcFilenames) {
	    LOGGER.info("Building hash of " + srcFilename);
	    buildHash(fs, srcFilename, signatureWidth, generateUnsigned, true, srcFileCharset, jsapResult.getBoolean(WRITE_INFO_ARG, false));
	}
	return 0;
    }

    public long buildHash(FileSystem fs, String srcFilename, int signatureWidth, boolean generateUnsigned, boolean keepUnsigned, final Charset charset, boolean writeInfoFile)
	    throws IOException, ClassNotFoundException {
	final MapReducePartInputStreamEnumeration inputStreamEnumeration;
	try {
	    inputStreamEnumeration = new MapReducePartInputStreamEnumeration(fs, new Path(srcFilename));
	} catch (IOException e) {
	    throw new RuntimeException("Failed to open " + srcFilename, e);
	}

	Collection<MutableString> inCollection = new LineReaderCollection(new LineReaderCollection.ReaderFactory() {
	    @Override
	    public Reader newReader() {
		inputStreamEnumeration.reset();
		return new InputStreamReader(new SequenceInputStream(inputStreamEnumeration), charset);
	    }
	});
	
	String destFilename = inputStreamEnumeration.removeCompressionSuffixIfAny(srcFilename);
	Path unsigendPath = new Path(destFilename + DOT_UNSIGNED);

	LcpMonotoneMinimalPerfectHashFunction<CharSequence> unsignedHash;
	if (generateUnsigned) {
	    LOGGER.info("\tBuilding unsigned hash...");
	    unsignedHash = new LcpMonotoneMinimalPerfectHashFunction<CharSequence>(inCollection, TransformationStrategies.prefixFreeUtf16());
	    if (signatureWidth <= 0 || keepUnsigned) {
		LOGGER.info("\tSaving unsigned hash as " + unsigendPath.toString());
		writeMapToFile(unsignedHash, fs, unsigendPath);
	    }
	} else {
	    LOGGER.info("\tLoading unsigned hash from " + unsigendPath.toString());
	    unsignedHash = readMpHashFromFile(fs, unsigendPath);
	}

	if (signatureWidth > 0) {
	    LOGGER.info("\tBuilding signed hash...");
	    ShiftAddXorSignedStringMap signedHash = new ShiftAddXorSignedStringMap(inCollection.iterator(), unsignedHash, signatureWidth);
	    Path signedPath = new Path(destFilename + DOT_SIGNED);
	    LOGGER.info("\tSaving signed hash as " + signedPath.toString());
	    writeMapToFile(signedHash, fs, signedPath);
	}

	if (writeInfoFile) {
	    Path infoPath = new Path(destFilename + DOT_MAPINFO);
	    FSDataOutputStream infoStream = fs.create(infoPath, true);// overwrite
	    fs.setPermission(infoPath, ALL_PERMISSIONS);
	    OutputStreamWriter infoWriter = new OutputStreamWriter(infoStream);
	    infoWriter.write("size\t");
	    infoWriter.write(Long.toString(unsignedHash.size64()));
	    infoWriter.write("\n");
	    if (keepUnsigned) {
		infoWriter.write("unsignedBits\t");
		infoWriter.write(Long.toString((unsignedHash).numBits()));
		infoWriter.write("\n");
	    }
	    if (signatureWidth > 0) {
		infoWriter.write("signedWidth\t");
		infoWriter.write(Integer.toString(signatureWidth));
		infoWriter.write("\n");
	    }
	    infoWriter.close();
	    infoStream.close();
	}

	return unsignedHash.size64();
    }

    private static void writeMapToFile(AbstractObject2LongFunction<CharSequence> object, FileSystem fs, Path path) throws IOException {
	FSDataOutputStream outStream = null;
	try {
	    outStream = fs.create(path, true);// overwrite
	    fs.setPermission(path, ALL_PERMISSIONS);
	    
	    ObjectOutputStream oOutStream = null;
	    try {
		oOutStream = new ObjectOutputStream(outStream);
		oOutStream.writeObject(object);
	    } finally {
		if (oOutStream != null) {
		    oOutStream.close();
		}
	    }
	} finally {
	    if (outStream != null) {
		outStream.close();
	    }
	}
    }

    @SuppressWarnings("unchecked")
    private static LcpMonotoneMinimalPerfectHashFunction<CharSequence> readMpHashFromFile(FileSystem fs, Path path) throws IOException, ClassNotFoundException {
	FSDataInputStream inStream = null;
	try {
	    inStream = fs.open(path);
	    ObjectInputStream oInStream = null;
	    try {
    	    	oInStream = new ObjectInputStream(inStream);
    	    	Object object = oInStream.readObject();
    	    	return (LcpMonotoneMinimalPerfectHashFunction<CharSequence>) object;
	    } finally {
		if (oInStream != null) {
		    oInStream.close();
		}
	    }
	} finally {
	    if (inStream != null) {
		inStream.close();
	    }
	}
    }

    public static void main(String[] args) throws Exception {
	int ret = ToolRunner.run(new ComputeHashTool(), args);
	System.exit(ret);
    }

    /**
     * Presents a Reader as a Collection of MutableStrings with each line read
     * from the Reader as an element of the Collection.
     * {@link LineReaderCollection.ReaderFactory.newReader} is called each time
     * {@link LineReaderCollection.iterator} is called. Only the current line
     * and next line are held in memory.
     * 
     * Note that {@link LineReaderCollection.LineReaderIterator.next} always
     * returns the same instance of MutableString(but with different contents)
     * for each instance of {@link LineReaderCollection.LineReaderIterator}.
     * 
     * @author tep
     * 
     */
    private static class LineReaderCollection extends AbstractCollection<MutableString> {
	private final ReaderFactory readerFactory;
	private int size = -1;

	public LineReaderCollection(ReaderFactory readerFactory) {
	    this.readerFactory = readerFactory;
	}

	public interface ReaderFactory {
	    public Reader newReader();
	}

	private class LineReaderIterator implements Iterator<MutableString>, SafelyCloseable {
	    private FastBufferedReader fbr;
	    private MutableString current = new MutableString();
	    private MutableString next = new MutableString();
	    private boolean advance = true;

	    public LineReaderIterator(Reader reader) {
		fbr = new FastBufferedReader(reader);
	    }

	    @Override
	    public boolean hasNext() {
		if (fbr == null) {
		    return false;
		}

		if (advance) {
		    try {
			if (fbr.readLine(next) == null) {
			    close();
			    return false;
			}
		    } catch (IOException e) {
			throw new RuntimeException(e);
		    }
		    advance = false;
		}
		return true;
	    }

	    @Override
	    public MutableString next() {
		if (advance) {
		    if (!hasNext()) {
			throw new NoSuchElementException();
		    }
		}
		current.replace(next);
		advance = true;
		return current;
	    }

	    @Override
	    public void remove() {
		throw new UnsupportedOperationException();
	    }

	    @Override
	    public void close() throws IOException {
		// This gets called multiple times..
		if (fbr != null) {
		    fbr.close();
		    fbr = null;
		    advance = false;
		}
	    }
	}

	@Override
	public LineReaderIterator iterator() {
	    return new LineReaderIterator(readerFactory.newReader());
	}

	@Override
	public int size() {
	    if (size == -1) {
		LineReaderIterator i = iterator();
		size = 0;
		while (i.hasNext()) {
		    size++;
		    i.next();
		}
		try {
		    i.close();
		} catch (IOException e) {
		    throw new RuntimeException(e);
		}
	    }
	    return size;
	}
    }
}
