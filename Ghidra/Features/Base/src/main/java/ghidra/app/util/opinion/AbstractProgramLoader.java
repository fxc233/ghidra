/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.app.util.opinion;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import ghidra.app.plugin.processors.generic.MemoryBlockDefinition;
import ghidra.app.util.Option;
import ghidra.app.util.OptionUtils;
import ghidra.app.util.bin.ByteProvider;
import ghidra.app.util.importer.MessageLog;
import ghidra.formats.gfilesystem.FSRL;
import ghidra.framework.model.*;
import ghidra.framework.store.LockException;
import ghidra.plugin.importer.ProgramMappingService;
import ghidra.program.database.ProgramDB;
import ghidra.program.database.function.OverlappingFunctionException;
import ghidra.program.model.address.*;
import ghidra.program.model.lang.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.InvalidAddressException;
import ghidra.program.model.mem.MemoryConflictException;
import ghidra.program.model.symbol.*;
import ghidra.program.util.DefaultLanguageService;
import ghidra.program.util.GhidraProgramUtilities;
import ghidra.util.*;
import ghidra.util.exception.*;
import ghidra.util.task.TaskMonitor;

/**
 * An abstract {@link Loader} that provides a framework to conveniently load {@link Program}s.
 * Subclasses are responsible for the actual load.
 * <p>
 * This {@link Loader} provides a couple processor-related options, as all {@link Program}s will
 * have a processor associated with them.
 */
public abstract class AbstractProgramLoader implements Loader {

	public static final String APPLY_LABELS_OPTION_NAME = "Apply Processor Defined Labels";
	public static final String ANCHOR_LABELS_OPTION_NAME = "Anchor Processor Defined Labels";

	/**
	 * A {@link Program} with its associated {@link DomainFolder destination folder}
	 * 
	 * @param program The {@link Program}
	 * @param destinationFolder The {@link DomainFolder} where the program will get loaded to
	 */
	public record LoadedProgram(Program program, DomainFolder destinationFolder) {/**/}

	/**
	 * Loads program bytes in a particular format as a new {@link Program}. Multiple
	 * {@link Program}s may end up getting created, depending on the nature of the format.
	 *
	 * @param provider The bytes to load.
	 * @param programName The name of the {@link Program} that's being loaded.
	 * @param programFolder The {@link DomainFolder} where the loaded thing should be saved.  Could
	 *   be null if the thing should not be pre-saved.
	 * @param loadSpec The {@link LoadSpec} to use during load.
	 * @param options The load options.
	 * @param log The message log.
	 * @param consumer A consumer object for {@link Program}s generated.
	 * @param monitor A cancelable task monitor.
	 * @return A list of {@link LoadedProgram loaded programs} (element 0 corresponds to primary 
	 *   loaded {@link Program}).
	 * @throws IOException if there was an IO-related problem loading.
	 * @throws CancelledException if the user cancelled the load.
	 */
	protected abstract List<LoadedProgram> loadProgram(ByteProvider provider, String programName,
			DomainFolder programFolder, LoadSpec loadSpec, List<Option> options, MessageLog log,
			Object consumer, TaskMonitor monitor) throws IOException, CancelledException;

	/**
	 * Loads program bytes into the specified {@link Program}.  This method will not create any new
	 * {@link Program}s.  It is only for adding to an existing {@link Program}.
	 * <p>
	 * NOTE: The loading that occurs in this method will automatically be done in a transaction.
	 *
	 * @param provider The bytes to load into the {@link Program}.
	 * @param loadSpec The {@link LoadSpec} to use during load.
	 * @param options The load options.
	 * @param messageLog The message log.
	 * @param program The {@link Program} to load into.
	 * @param monitor A cancelable task monitor.
	 * @return True if the file was successfully loaded; otherwise, false.
	 * @throws IOException if there was an IO-related problem loading.
	 * @throws CancelledException if the user cancelled the load.
	 */
	protected abstract boolean loadProgramInto(ByteProvider provider, LoadSpec loadSpec,
			List<Option> options, MessageLog messageLog, Program program, TaskMonitor monitor)
			throws IOException, CancelledException;

	@Override
	public final List<DomainObject> load(ByteProvider provider, String name, DomainFolder folder,
			LoadSpec loadSpec, List<Option> options, MessageLog messageLog, Object consumer,
			TaskMonitor monitor) throws IOException, CancelledException, InvalidNameException,
			DuplicateNameException, VersionException {

		if (!isOverrideMainProgramName()) {
			folder = ProjectDataUtils.createDomainFolderPath(folder, name);
		}

		List<DomainObject> results = new ArrayList<>();

		if (!loadSpec.isComplete()) {
			return results;
		}

		List<LoadedProgram> loadedPrograms =
			loadProgram(provider, name, folder, loadSpec, options, messageLog, consumer, monitor);

		boolean success = false;
		try {
			monitor.checkCanceled();
			for (LoadedProgram loadedProgram : loadedPrograms) {
				monitor.checkCanceled();

				Program program = loadedProgram.program();

				applyProcessorLabels(options, program);

				program.setEventsEnabled(true);

				// TODO: null should not be used as a determinant for saving; don't allow null
				// folders?
				if (loadedProgram.destinationFolder() == null) {
					results.add(program);
					continue;
				}

				String domainFileName = program.getName();
				if (isOverrideMainProgramName()) {
					// If this is the main imported program, use the given name, otherwise, use the
					// internal program name. The first program in the list is the main imported program
					if (program == loadedPrograms.get(0).program()) {
						domainFileName = name;
					}
				}

				if (createProgramFile(program, loadedProgram.destinationFolder(), domainFileName,
					messageLog, monitor)) {
					results.add(program);
				}
				else {
					program.release(consumer); // some kind of exception happened; see MessageLog
				}
			}

			// Subclasses can perform custom post-load fix-ups
			postLoadProgramFixups(loadedPrograms, options, messageLog, monitor);

			success = true;
		}
		finally {
			if (!success) {
				release(loadedPrograms, consumer);
			}
		}

		return results;
	}

	/**
	 * Some loaders can return more than one program.
	 * This method indicates whether the first (or main) program's name 
	 * should be overridden and changed to the imported file name.
	 * @return true if first program name should be changed
	 */
	protected boolean isOverrideMainProgramName() {
		return true;
	}

	@Override
	public final boolean loadInto(ByteProvider provider, LoadSpec loadSpec, List<Option> options,
			MessageLog messageLog, Program program, TaskMonitor monitor)
			throws IOException, CancelledException {

		if (!loadSpec.isComplete()) {
			return false;
		}

		program.setEventsEnabled(false);
		int transactionID = program.startTransaction("Loading - " + getName());
		boolean success = false;
		try {
			success = loadProgramInto(provider, loadSpec, options, messageLog, program, monitor);
			return success;
		}
		finally {
			program.endTransaction(transactionID, success);
			program.setEventsEnabled(true);
		}
	}

	@Override
	public List<Option> getDefaultOptions(ByteProvider provider, LoadSpec loadSpec,
			DomainObject domainObject, boolean isLoadIntoProgram) {
		ArrayList<Option> list = new ArrayList<>();
		list.add(new Option(APPLY_LABELS_OPTION_NAME, shouldApplyProcessorLabelsByDefault(),
			Boolean.class, Loader.COMMAND_LINE_ARG_PREFIX + "-applyLabels"));
		list.add(new Option(ANCHOR_LABELS_OPTION_NAME, true, Boolean.class,
			Loader.COMMAND_LINE_ARG_PREFIX + "-anchorLabels"));

		return list;
	}

	@Override
	public String validateOptions(ByteProvider provider, LoadSpec loadSpec, List<Option> options,
			Program program) {
		if (options != null) {
			for (Option option : options) {
				String name = option.getName();
				if (name.equals(APPLY_LABELS_OPTION_NAME) ||
					name.equals(ANCHOR_LABELS_OPTION_NAME)) {
					if (!Boolean.class.isAssignableFrom(option.getValueClass())) {
						return "Invalid type for option: " + name + " - " + option.getValueClass();
					}
				}
			}
		}
		return null;
	}

	/**
	 * This gets called after the given list of {@link LoadedProgram programs}s is finished loading.
	 * It provides subclasses an opportunity to do follow-on actions to the load.
	 *
	 * @param loadedPrograms The {@link LoadedProgram programs} that got loaded.
	 * @param options The load options.
	 * @param messageLog The message log.
	 * @param monitor A cancelable task monitor.
	 * @throws IOException if there was an IO-related problem loading.
	 * @throws CancelledException if the user cancelled the load.
	 */
	protected void postLoadProgramFixups(List<LoadedProgram> loadedPrograms, List<Option> options,
			MessageLog messageLog, TaskMonitor monitor) throws CancelledException, IOException {
		// Default behavior is to do nothing.
	}

	/**
	 * Returns whether or not processor labels should be applied by default.  Most loaders will
	 * not need to override this method because they will not want the labels applied by default.
	 *
	 * @return Whether or not processor labels should be applied by default.
	 */
	protected boolean shouldApplyProcessorLabelsByDefault() {
		return false;
	}

	/**
	 * Generates a block name.
	 *
	 * @param program The {@link Program} for the block.
	 * @param isOverlay true if the block is an overlay; use "ov" in the name.
	 * @param space The {@link AddressSpace} for the block.
	 * @return The generated block name.
	 */
	protected String generateBlockName(Program program, boolean isOverlay, AddressSpace space) {
		if (!isOverlay) {
			return space.getName();
		}
		AddressFactory factory = program.getAddressFactory();
		int count = 0;
		while (count < 1000) {
			String lname = "ov" + (++count);
			if (factory.getAddressSpace(lname) == null) {
				return lname;
			}
		}
		return "ov" + System.currentTimeMillis(); // CAN'T HAPPEN
	}

	/**
	 * Creates a {@link Program} with the specified attributes.
	 *
	 * @param provider The bytes that will make up the {@link Program}.
	 * @param domainFileName The name for the DomainFile that will store the {@link Program}.
	 * @param imageBase  The image base address of the {@link Program}.
	 * @param executableFormatName The file format name of the {@link Program}.  Typically this will
	 *   be the {@link Loader} name.
	 * @param language The {@link Language} of the {@link Program}.
	 * @param compilerSpec The {@link CompilerSpec} of the {@link Program}.
	 * @param consumer A consumer object for the {@link Program} generated.
	 * @return The newly created {@link Program}.
	 * @throws IOException if there was an IO-related problem with creating the {@link Program}.
	 */
	protected Program createProgram(ByteProvider provider, String domainFileName,
			Address imageBase, String executableFormatName, Language language,
			CompilerSpec compilerSpec, Object consumer) throws IOException {

		String programName = getProgramNameFromSourceData(provider, domainFileName);
		Program prog = new ProgramDB(programName, language, compilerSpec, consumer);
		prog.setEventsEnabled(false);
		int id = prog.startTransaction("Set program properties");
		try {
			setProgramProperties(prog, provider, executableFormatName);

			if (shouldSetImageBase(prog, imageBase)) {
				try {
					prog.setImageBase(imageBase, true);
				}
				catch (AddressOverflowException e) {
					// can't happen here
				}
				catch (LockException e) {
					// can't happen here
				}
			}
		}
		finally {
			prog.endTransaction(id, true);
		}
		return prog;
	}

	/**
	 * Sets a program's Executable Path, Executable Format, MD5, SHA256, and FSRL properties.
	 * <p>
	 *  
	 * @param prog {@link Program} (with active transaction)
	 * @param provider {@link ByteProvider} that the program was created from
	 * @param executableFormatName executable format string
	 * @throws IOException if error reading from ByteProvider
	 */
	public static void setProgramProperties(Program prog, ByteProvider provider,
			String executableFormatName) throws IOException {
		prog.setExecutablePath(provider.getAbsolutePath());
		if (executableFormatName != null) {
			prog.setExecutableFormat(executableFormatName);
		}
		FSRL fsrl = provider.getFSRL();
		String md5 = (fsrl != null && fsrl.getMD5() != null)
				? fsrl.getMD5()
				: computeBinaryMD5(provider);
		if (fsrl != null) {
			if (fsrl.getMD5() == null) {
				fsrl = fsrl.withMD5(md5);
			}
			prog.getOptions(Program.PROGRAM_INFO)
					.setString(ProgramMappingService.PROGRAM_SOURCE_FSRL, fsrl.toString());
		}
		prog.setExecutableMD5(md5);
		String sha256 = computeBinarySHA256(provider);
		prog.setExecutableSHA256(sha256);
	}

	private String getProgramNameFromSourceData(ByteProvider provider, String domainFileName) {
		FSRL fsrl = provider.getFSRL();
		if (fsrl != null) {
			return fsrl.getName();
		}

		// If the ByteProvider dosn't have have an FSRL, use the given domainFileName
		return domainFileName;
	}

	/**
	 * Creates default memory blocks for the given {@link Program}.
	 *
	 * @param program The {@link Program} to create default memory blocks for.
	 * @param language The {@link Program}s {@link Language}.
	 * @param log The log to use during memory block creation.
	 */
	protected void createDefaultMemoryBlocks(Program program, Language language, MessageLog log) {
		int id = program.startTransaction("Create default blocks");
		try {

			MemoryBlockDefinition[] defaultMemoryBlocks = language.getDefaultMemoryBlocks();
			if (defaultMemoryBlocks == null) {
				return;
			}
			for (MemoryBlockDefinition blockDef : defaultMemoryBlocks) {
				try {
					blockDef.createBlock(program);
				}
				catch (LockException e) {
					throw new AssertException("Unexpected Error");
				}
				catch (MemoryConflictException e) {
					log.appendMsg(
						"Failed to add language defined memory block due to conflict: " + blockDef);
				}
				catch (AddressOverflowException e) {
					log.appendMsg(
						"Failed to add language defined memory block due to address error " +
							blockDef);
					log.appendMsg(" >> " + e.getMessage());
				}
				catch (InvalidAddressException e) {
					log.appendMsg(
						"Failed to add language defined memory block due to invalid address: " +
							blockDef);
					log.appendMsg(" >> Processor specification error (pspec): " + e.getMessage());
				}
			}
		}
		finally {
			program.endTransaction(id, true);
		}
	}

	/**
	 * Mark this address as a function by creating a one byte function.  The single byte body
	 * function is picked up by the function analyzer, disassembled, and the body fixed.
	 * Marking the function this way keeps disassembly and follow on analysis out of the loaders.
	 * 
	 * @param program the program
	 * @param name name of function, null if name not known
	 * @param funcStart starting address of the function
	 */
	public static void markAsFunction(Program program, String name, Address funcStart) {
		FunctionManager functionMgr = program.getFunctionManager();

		if (functionMgr.getFunctionAt(funcStart) != null) {
			return;
		}
		try {
			functionMgr.createFunction(name, funcStart, new AddressSet(funcStart, funcStart),
				SourceType.IMPORTED);
		}
		catch (InvalidInputException e) {
			// ignore
		}
		catch (OverlappingFunctionException e) {
			// ignore
		}
	}

	/**
	 * Gets the {@link Loader}'s language service.
	 * <p>
	 * The default behavior of this method is to return the {@link DefaultLanguageService}.
	 *
	 * @return The {@link Loader}'s language service.
	 */
	protected LanguageService getLanguageService() {
		return DefaultLanguageService.getLanguageService();
	}

	/**
	 * Releases the given consumer from each of the provided {@link LoadedProgram}s.
	 *
	 * @param loadedPrograms A list of {@link LoadedProgram}s which are no longer being used.
	 * @param consumer The consumer that was marking the {@link DomainObject}s as being used.
	 */
	protected final void release(List<LoadedProgram> loadedPrograms, Object consumer) {
		for (LoadedProgram loadedProgram : loadedPrograms) {
			loadedProgram.program().release(consumer);
		}
	}

	private boolean createProgramFile(Program program, DomainFolder programFolder,
			String programName, MessageLog messageLog, TaskMonitor monitor)
			throws CancelledException, InvalidNameException {

		int uniqueNameIndex = 0;
		String uniqueName = programName;
		while (!monitor.isCancelled()) {
			try {
				programFolder.createFile(uniqueName, program, monitor);
				break;
			}
			catch (DuplicateFileException e) {
				uniqueName = programName + uniqueNameIndex;
				++uniqueNameIndex;
			}
			catch (CancelledException | InvalidNameException e) {
				throw e;
			}
			catch (Exception e) {
				Throwable t = e.getCause();
				if (t == null) {
					t = e;
				}
				String msg = t.getMessage();
				if (msg == null) {
					msg = "";
				}
				else {
					msg = "\n" + msg;
				}
				Msg.showError(this, null, "Create Program Failed",
					"Failed to create program file: " + uniqueName + msg, e);
				messageLog.appendMsg("Unexpected exception creating file: " + uniqueName);
				messageLog.appendException(e);
				return false;
			}
		}

		// makes the data tree expand to show new file!
		// The following line was disabled as it causes UI updates that are better
		// done by the callers to this loader instead of this loader.
		//programFolder.setActive();
		return true;
	}

	private void applyProcessorLabels(List<Option> options, Program program) {
		int id = program.startTransaction("Finalize load");
		try {
			Language lang = program.getLanguage();
			// always create anchored symbols for memory mapped registers
			// which may be explicitly referenced by pcode
			for (Register reg : lang.getRegisters()) {
				Address addr = reg.getAddress();
				if (addr.isMemoryAddress()) {
					createSymbol(program, reg.getName(), addr, false, true, true);
				}
			}
			// optionally create default symbols defined by pspec
			if (shouldApplyProcessorLabels(options)) {
				boolean anchorSymbols = shouldAnchorSymbols(options);
				List<AddressLabelInfo> labels = lang.getDefaultSymbols();
				for (AddressLabelInfo info : labels) {
					createSymbol(program, info.getLabel(), info.getAddress(), info.isEntry(), info.isPrimary(), anchorSymbols);
				}
			}
			GhidraProgramUtilities.removeAnalyzedFlag(program);
		}
		finally {
			program.endTransaction(id, true);
		}
	}

	private static void createSymbol(Program program, String labelname, Address address, boolean isEntry, boolean isPrimary, boolean anchorSymbols) {
		SymbolTable symTable = program.getSymbolTable();
		Address addr = address;
		Symbol s = symTable.getPrimarySymbol(addr);
		try {
			Namespace namespace = program.getGlobalNamespace();
			s = symTable.createLabel(addr, labelname, namespace, SourceType.IMPORTED);
			if (isEntry) {
				symTable.addExternalEntryPoint(addr);
			}
			if (isPrimary) {
				s.setPrimary();
			}
			if (anchorSymbols) {
				s.setPinned(true);
			}
		}
		catch (InvalidInputException e) {
			// Nothing to do
		}
	}

	private static String computeBinaryMD5(ByteProvider provider) throws IOException {
		try (InputStream in = provider.getInputStream(0)) {
			return MD5Utilities.getMD5Hash(in);
		}
	}

	private static String computeBinarySHA256(ByteProvider provider) throws IOException {
		try (InputStream in = provider.getInputStream(0)) {
			return HashUtilities.getHash(HashUtilities.SHA256_ALGORITHM, in);
		}
	}

	private boolean shouldSetImageBase(Program prog, Address imageBase) {
		if (imageBase == null || imageBase instanceof SegmentedAddress) {
			return false;
		}
		return imageBase.getAddressSpace() == prog.getAddressFactory().getDefaultAddressSpace();
	}

	private boolean shouldApplyProcessorLabels(List<Option> options) {
		return OptionUtils.getBooleanOptionValue(APPLY_LABELS_OPTION_NAME, options, true);
	}

	private boolean shouldAnchorSymbols(List<Option> options) {
		return OptionUtils.getBooleanOptionValue(ANCHOR_LABELS_OPTION_NAME, options, true);
	}
}
