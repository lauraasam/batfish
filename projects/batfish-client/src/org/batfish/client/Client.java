package org.batfish.client;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.output.WriterOutputStream;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.batfish.client.Settings.RunMode;
import org.batfish.common.BatfishException;
import org.batfish.common.BfConsts;
import org.batfish.common.BatfishLogger;
import org.batfish.common.Pair;
import org.batfish.common.Task;
import org.batfish.common.Task.Batch;
import org.batfish.common.WorkItem;
import org.batfish.common.CoordConsts.WorkStatusCode;
import org.batfish.common.plugin.AbstractClient;
import org.batfish.common.plugin.IClient;
import org.batfish.common.util.BatfishObjectMapper;
import org.batfish.common.util.CommonUtil;
import org.batfish.common.util.ZipUtility;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.answers.Answer;
import org.batfish.datamodel.questions.IEnvironmentCreationQuestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kjetland.jackson.jsonSchema.JsonSchemaGenerator;

import jline.console.ConsoleReader;
import jline.console.completer.Completer;
import jline.console.history.FileHistory;

public class Client extends AbstractClient implements IClient {

   private static final String DEFAULT_CONTAINER_PREFIX = "cp";

   private static final String DEFAULT_DELTA_ENV_PREFIX = "env_";

   private static final String DEFAULT_ENV_NAME = BfConsts.RELPATH_DEFAULT_ENVIRONMENT_NAME;

   private static final String DEFAULT_QUESTION_PREFIX = "q";

   private static final String DEFAULT_TESTRIG_PREFIX = "tr_";

   private static final String ENV_HOME = "HOME";

   private static final String FLAG_FAILING_TEST = "-error";

   private static final String HISTORY_FILE = ".batfishclient_history";

   private static final int NUM_TRIES_WARNING_THRESHOLD = 5;

   private static final String STARTUP_FILE = ".batfishclientrc";

   private Map<String, String> _additionalBatfishOptions;

   private String _currContainerName = null;

   private String _currDeltaEnv = null;

   private String _currDeltaTestrig;

   private String _currEnv = null;

   private String _currTestrig = null;

   private boolean _exit;

   private BatfishLogger _logger;

   @SuppressWarnings("unused")
   private BfCoordPoolHelper _poolHelper;

   private ConsoleReader _reader;

   private Settings _settings;

   private BfCoordWorkHelper _workHelper;

   public Client(Settings settings) {
      super(false, settings.getPluginDirs());
      _additionalBatfishOptions = new HashMap<>();
      _settings = settings;

      switch (_settings.getRunMode()) {
      case batch:
         if (_settings.getBatchCommandFile() == null) {
            System.err.println(
                  "org.batfish.client: Command file not specified while running in batch mode.");
            System.err.printf(
                  "Use '-%s <cmdfile>' if you want batch mode, or '-%s interactive' if you want interactive mode\n",
                  Settings.ARG_COMMAND_FILE, Settings.ARG_RUN_MODE);
            System.exit(1);
         }
         _logger = new BatfishLogger(_settings.getLogLevel(), false,
               _settings.getLogFile(), false, false);
         break;
      case gendatamodel:
         _logger = new BatfishLogger(_settings.getLogLevel(), false,
               _settings.getLogFile(), false, false);
         break;
      case genquestions:
         if (_settings.getQuestionsDir() == null) {
            System.err.println(
                  "org.batfish.client: Out dir not specified while running in genquestions mode.");
            System.err.printf("Use '-%s <cmdfile>'\n",
                  Settings.ARG_QUESTIONS_DIR);
            System.exit(1);
         }
         _logger = new BatfishLogger(_settings.getLogLevel(), false,
               _settings.getLogFile(), false, false);
         break;
      case interactive:
         try {
            _reader = new ConsoleReader();
            Path historyPath = Paths.get(System.getenv(ENV_HOME), HISTORY_FILE);
            historyPath.toFile().createNewFile();
            FileHistory history = new FileHistory(historyPath.toFile());
            _reader.setHistory(history);
            _reader.setPrompt("batfish> ");
            _reader.setExpandEvents(false);

            List<Completer> completors = new LinkedList<>();
            completors.add(new CommandCompleter());

            for (Completer c : completors) {
               _reader.addCompleter(c);
            }

            PrintWriter pWriter = new PrintWriter(_reader.getOutput(), true);
            OutputStream os = new WriterOutputStream(pWriter);
            PrintStream ps = new PrintStream(os, true);
            _logger = new BatfishLogger(_settings.getLogLevel(), false, ps);
         }
         catch (Exception e) {
            System.err.printf("Could not initialize client: %s\n",
                  e.getMessage());
            e.printStackTrace();
            System.exit(1);
         }
         break;
      default:
         System.err.println("org.batfish.client: Unknown run mode.");
         System.exit(1);
      }

   }

   public Client(String[] args) throws Exception {
      this(new Settings(args));
   }

   private boolean addBatfishOption(String[] words, List<String> options,
         List<String> parameters) {
      String optionKey = parameters.get(0);
      String optionValue = String.join(" ",
            Arrays.copyOfRange(words, 2 + options.size(), words.length));
      _additionalBatfishOptions.put(optionKey, optionValue);
      return true;
   }

   private boolean answer(String[] words, FileWriter outWriter,
         List<String> options, List<String> parameters) throws Exception {
      if (!isSetTestrig() || !isSetContainer(true)) {
         return false;
      }

      String questionFile = parameters.get(0);
      String paramsLine = String.join(" ",
            Arrays.copyOfRange(words, 2 + options.size(), words.length));

      return answerFile(questionFile, paramsLine, false, outWriter);
   }

   private boolean answerDelta(String[] words, FileWriter outWriter,
         List<String> options, List<String> parameters) throws Exception {
      if (!isSetDeltaEnvironment() || !isSetTestrig()
            || !isSetContainer(true)) {
         return false;
      }

      String questionFile = parameters.get(0);
      String paramsLine = String.join(" ",
            Arrays.copyOfRange(words, 2 + options.size(), words.length));

      return answerFile(questionFile, paramsLine, true, outWriter);
   }

   private boolean answerFile(String questionFile, String paramsLine,
         boolean isDelta, FileWriter outWriter) throws Exception {

      if (!new File(questionFile).exists()) {
         throw new FileNotFoundException(
               "Question file not found: " + questionFile);
      }

      String questionName = DEFAULT_QUESTION_PREFIX + "_"
            + UUID.randomUUID().toString();

      File paramsFile = createTempFile("parameters", paramsLine);
      paramsFile.deleteOnExit();

      // upload the question
      boolean resultUpload = _workHelper.uploadQuestion(_currContainerName,
            isDelta ? _currDeltaTestrig : _currTestrig, questionName,
            questionFile, paramsFile.getAbsolutePath());

      if (!resultUpload) {
         return false;
      }

      _logger.debug("Uploaded question. Answering now.\n");

      // delete the temporary params file
      if (paramsFile != null) {
         paramsFile.delete();
      }

      // answer the question
      WorkItem wItemAs = _workHelper.getWorkItemAnswerQuestion(questionName,
            _currContainerName, _currTestrig, _currEnv, _currDeltaTestrig,
            _currDeltaEnv, isDelta);

      return execute(wItemAs, outWriter);
   }

   private boolean answerType(String questionType, String paramsLine,
         boolean isDelta, FileWriter outWriter) throws Exception {

      Map<String, String> parameters = parseParams(paramsLine);

      String questionString;
      String parametersString = "";
      if (questionType.startsWith(QuestionHelper.MACRO_PREFIX)) {
         try {
            questionString = QuestionHelper.resolveMacro(questionType,
                  paramsLine, _questions);
         }
         catch (BatfishException e) {
            _logger.errorf("Could not resolve macro: %s\n", e.getMessage());
            return false;
         }
      }
      else {
         questionString = QuestionHelper.getQuestionString(questionType,
               _questions, false);
         _logger.debugf("Question Json:\n%s\n", questionString);

         parametersString = QuestionHelper.getParametersString(parameters);
         _logger.debugf("Parameters Json:\n%s\n", parametersString);
      }

      File questionFile = createTempFile("question", questionString);

      boolean result = answerFile(questionFile.getAbsolutePath(),
            parametersString, isDelta, outWriter);

      if (questionFile != null) {
         questionFile.delete();
      }

      return result;
   }

   private boolean cat(String[] words)
         throws IOException, FileNotFoundException {
      String filename = words[1];

      try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
         String line = null;
         while ((line = br.readLine()) != null) {
            _logger.output(line + "\n");
         }
      }

      return true;
   }

   private boolean checkApiKey() {
      String isValid = _workHelper.checkApiKey();
      _logger.outputf("Api key validitiy: %s\n", isValid);
      return true;
   }

   private boolean clearScreen() throws IOException {
      _reader.clearScreen();
      return false;
   }

   private File createTempFile(String filePrefix, String content)
         throws IOException {

      File tempFile = Files.createTempFile(filePrefix, null).toFile();
      tempFile.deleteOnExit();

      _logger.debugf("Creating temporary %s file: %s\n", filePrefix,
            tempFile.getAbsolutePath());

      FileWriter writer = new FileWriter(tempFile);
      writer.write(content + "\n");
      writer.close();

      return tempFile;
   }

   private boolean delBatfishOption(List<String> parameters) {
      String optionKey = parameters.get(0);

      if (!_additionalBatfishOptions.containsKey(optionKey)) {
         _logger.outputf("Batfish option %s does not exist\n", optionKey);
         return false;
      }
      _additionalBatfishOptions.remove(optionKey);
      return true;
   }

   private boolean delContainer(List<String> parameters) {
      String containerName = parameters.get(0);
      boolean result = _workHelper.delContainer(containerName);
      _logger.outputf("Result of deleting container: %s\n", result);
      return true;
   }

   private boolean delEnvironment(List<String> parameters) {
      if (!isSetTestrig() || !isSetContainer(true)) {
         return false;
      }

      String envName = parameters.get(0);
      boolean result = _workHelper.delEnvironment(_currContainerName,
            _currTestrig, envName);
      _logger.outputf("Result of deleting environment: %s\n", result);
      return true;
   }

   private boolean delQuestion(List<String> parameters) {
      if (!isSetTestrig() || !isSetContainer(true)) {
         return false;
      }

      String qName = parameters.get(0);
      boolean result = _workHelper.delQuestion(_currContainerName, _currTestrig,
            qName);
      _logger.outputf("Result of deleting question: %s\n", result);
      return true;
   }

   private boolean delTestrig(List<String> parameters) {
      if (!isSetContainer(true)) {
         return false;
      }

      String testrigName = parameters.get(0);
      boolean result = _workHelper.delTestrig(_currContainerName, testrigName);
      _logger.outputf("Result of deleting testrig: %s\n", result);
      return true;
   }

   private boolean dir(List<String> parameters) {
      String dirname = (parameters.size() == 1) ? parameters.get(0) : ".";
      File currDirectory = new File(dirname);
      for (File file : currDirectory.listFiles()) {
         _logger.output(file.getName() + "\n");
      }
      return true;
   }

   private boolean echo(String[] words) {
      _logger.outputf("%s\n",
            String.join(" ", Arrays.copyOfRange(words, 1, words.length)));
      return true;
   }

   private boolean execute(WorkItem wItem, FileWriter outWriter)
         throws Exception {

      _logger.info("work-id is " + wItem.getId() + "\n");

      wItem.addRequestParam(BfConsts.ARG_LOG_LEVEL,
            _settings.getBatfishLogLevel());

      for (String option : _additionalBatfishOptions.keySet()) {
         wItem.addRequestParam(option, _additionalBatfishOptions.get(option));
      }

      boolean queueWorkResult = _workHelper.queueWork(wItem);
      _logger.info("Queuing result: " + queueWorkResult + "\n");

      if (!queueWorkResult) {
         return queueWorkResult;
      }

      Pair<WorkStatusCode, String> response = _workHelper
            .getWorkStatus(wItem.getId());

      while (response.getFirst() != WorkStatusCode.TERMINATEDABNORMALLY
            && response.getFirst() != WorkStatusCode.TERMINATEDNORMALLY
            && response.getFirst() != WorkStatusCode.ASSIGNMENTERROR) {
         printWorkStatusResponse(response);
         Thread.sleep(1 * 1000);
         response = _workHelper.getWorkStatus(wItem.getId());
      }
      printWorkStatusResponse(response);

      // get the answer
      String ansFileName = wItem.getId() + BfConsts.SUFFIX_ANSWER_JSON_FILE;
      String downloadedAnsFile = _workHelper.getObject(wItem.getContainerName(),
            wItem.getTestrigName(), ansFileName);

      if (downloadedAnsFile == null) {
         _logger.errorf(
               "Failed to get answer file %s. Fix batfish and remove the statement below this line\n",
               ansFileName);
         // return false;
      }
      else {
         String answerString = CommonUtil
               .readFile(Paths.get(downloadedAnsFile));

         // Check if we need to make things pretty
         // Don't if we are writing to FileWriter, because we need valid JSON in
         // that case
         String answerStringToPrint = answerString;
         if (outWriter == null && _settings.getPrettyPrintAnswers()) {
            ObjectMapper mapper = new BatfishObjectMapper(
                  getCurrentClassLoader());
            Answer answer = mapper.readValue(answerString, Answer.class);
            answerStringToPrint = answer.prettyPrint();
         }

         if (outWriter == null) {
            _logger.output(answerStringToPrint);
         }
         else {
            outWriter.write(answerStringToPrint);
         }

         // tests serialization/deserialization when running in debug mode
         if (_logger.getLogLevel() >= BatfishLogger.LEVEL_DEBUG) {
            try {
               ObjectMapper mapper = new BatfishObjectMapper(
                     getCurrentClassLoader());
               Answer answer = mapper.readValue(answerString, Answer.class);

               String newAnswerString = mapper.writeValueAsString(answer);
               JsonNode tree = mapper.readTree(answerString);
               JsonNode newTree = mapper.readTree(newAnswerString);
               if (!CommonUtil.checkJsonEqual(tree, newTree)) {
                  // if (!tree.equals(newTree)) {
                  _logger.errorf(
                        "Original and recovered Json are different. Recovered = %s\n",
                        newAnswerString);
               }
            }
            catch (Exception e) {
               _logger.outputf("Could NOT deserialize Json to Answer: %s\n",
                     e.getMessage());
            }
         }
      }

      // get and print the log when in debugging mode
      if (_logger.getLogLevel() >= BatfishLogger.LEVEL_DEBUG) {
         _logger.output("---------------- Service Log --------------\n");
         String logFileName = wItem.getId() + BfConsts.SUFFIX_LOG_FILE;
         String downloadedFile = _workHelper.getObject(wItem.getContainerName(),
               wItem.getTestrigName(), logFileName);

         if (downloadedFile == null) {
            _logger.errorf("Failed to get log file %s\n", logFileName);
            return false;
         }
         else {
            try (BufferedReader br = new BufferedReader(
                  new FileReader(downloadedFile))) {
               String line = null;
               while ((line = br.readLine()) != null) {
                  _logger.output(line + "\n");
               }
            }
         }
      }

      if (response.getFirst() == WorkStatusCode.TERMINATEDNORMALLY) {
         return true;
      }
      else {
         // _logger.errorf("WorkItem failed: %s", wItem);
         return false;
      }
   }

   private boolean exit() {
      _exit = true;
      return true;
   }

   private void generateDatamodel() {
      try {
         ObjectMapper mapper = new BatfishObjectMapper();

         JsonSchemaGenerator schemaGenNew = new JsonSchemaGenerator(mapper);
         JsonNode schemaNew = schemaGenNew
               .generateJsonSchema(Configuration.class);
         _logger.output(mapper.writeValueAsString(schemaNew));

         // JsonSchemaGenerator schemaGenNew = new JsonSchemaGenerator(mapper,
         // true, JsonSchemaConfig.vanillaJsonSchemaDraft4());
         // JsonNode schemaNew =
         // schemaGenNew.generateJsonSchema(Configuration.class);
         // _logger.output(mapper.writeValueAsString(schemaNew));

         // _logger.output("\n");
         // JsonNode schemaNew2 =
         // schemaGenNew.generateJsonSchema(SchemaTest.Parent.class);
         // _logger.output(mapper.writeValueAsString(schemaNew2));
      }
      catch (Exception e) {
         _logger.errorf("Could not generate data model: " + e.getMessage());
         e.printStackTrace();
      }
   }

   private boolean generateDataplane(FileWriter outWriter) throws Exception {
      if (!isSetTestrig() || !isSetContainer(true)) {
         return false;
      }

      // generate the data plane
      WorkItem wItemGenDp = _workHelper.getWorkItemGenerateDataPlane(
            _currContainerName, _currTestrig, _currEnv);

      return execute(wItemGenDp, outWriter);
   }

   private boolean generateDeltaDataplane(FileWriter outWriter)
         throws Exception {
      if (!isSetDeltaEnvironment() || !isSetTestrig()
            || !isSetContainer(true)) {
         return false;
      }

      WorkItem wItemGenDdp = _workHelper.getWorkItemGenerateDeltaDataPlane(
            _currContainerName, _currTestrig, _currEnv, _currDeltaTestrig,
            _currDeltaEnv);

      return execute(wItemGenDdp, outWriter);
   }

   private void generateQuestions() {

      File questionsDir = Paths.get(_settings.getQuestionsDir()).toFile();

      if (!questionsDir.exists()) {
         if (!questionsDir.mkdirs()) {
            _logger.errorf("Could not create questions dir %s\n",
                  _settings.getQuestionsDir());
            System.exit(1);
         }
      }

      _questions.forEach((qName, supplier) -> {
         try {
            String questionString = QuestionHelper.getQuestionString(qName,
                  _questions, true);
            String qFile = Paths
                  .get(_settings.getQuestionsDir(), qName + ".json").toFile()
                  .getAbsolutePath();

            PrintWriter writer = new PrintWriter(qFile);
            writer.write(questionString);
            writer.close();
         }
         catch (Exception e) {
            _logger.errorf("Could not write question %s: %s\n", qName,
                  e.getMessage());
         }
      });
   }

   private boolean get(String[] words, FileWriter outWriter,
         List<String> options, List<String> parameters, boolean isDelta)
         throws Exception {
      if (!isSetTestrig() || !isSetContainer(true)
            || (isDelta && !isSetDeltaEnvironment())) {
         return false;
      }
      String qTypeStr = parameters.get(0).toLowerCase();
      String paramsLine = String.join(" ",
            Arrays.copyOfRange(words, 2 + options.size(), words.length));
      // TODO: make environment creation a command, not a question
      if (!qTypeStr.startsWith(QuestionHelper.MACRO_PREFIX)
            && qTypeStr.equals(IEnvironmentCreationQuestion.NAME)) {

         String deltaEnvName = DEFAULT_DELTA_ENV_PREFIX
               + UUID.randomUUID().toString();

         String prefixString = (paramsLine.trim().length() > 0) ? " | " : "";
         paramsLine += String.format("%s %s=%s", prefixString,
               IEnvironmentCreationQuestion.ENVIRONMENT_NAME_KEY, deltaEnvName);

         if (!answerType(qTypeStr, paramsLine, isDelta, outWriter)) {
            unsetTestrig(true);
            return false;
         }

         _currDeltaEnv = deltaEnvName;
         _currDeltaTestrig = _currTestrig;

         _logger.output("Active delta testrig->environment is set ");
         _logger.infof("to %s->%s\n", _currDeltaTestrig, _currDeltaEnv);
         _logger.output("\n");

         return true;
      }
      else {
         return answerType(qTypeStr, paramsLine, isDelta, outWriter);
      }
   }

   private boolean getAnswer(List<String> options, List<String> parameters) {
      if (!isSetTestrig() || !isSetContainer(true)) {
         return false;
      }

      boolean formatJson = true;

      if (options.size() == 1) {
         if (options.get(0).equals("-html")) {
            formatJson = false;
         }
         else {
            _logger.outputf(
                  "Unknown option: %s (note that json does not need a flag)\n",
                  options.get(0));
            return false;
         }
      }

      String questionName = parameters.get(0);

      String answerFileName = String.format("%s/%s/%s",
            BfConsts.RELPATH_QUESTIONS_DIR, questionName,
            (formatJson) ? BfConsts.RELPATH_ANSWER_JSON
                  : BfConsts.RELPATH_ANSWER_HTML);

      String downloadedAnsFile = _workHelper.getObject(_currContainerName,
            _currTestrig, answerFileName);
      if (downloadedAnsFile == null) {
         _logger.errorf("Failed to get answer file %s\n", answerFileName);
         return false;
      }

      String answerString = CommonUtil.readFile(Paths.get(downloadedAnsFile));
      _logger.output(answerString);
      _logger.output("\n");

      return true;
   }

   private List<String> getCommandOptions(String[] words) {
      List<String> options = new LinkedList<>();

      int currIndex = 1;

      while (currIndex < words.length && words[currIndex].startsWith("-")) {
         options.add(words[currIndex]);
         currIndex++;
      }

      return options;
   }

   private List<String> getCommandParameters(String[] words, int numOptions) {
      List<String> parameters = new LinkedList<>();

      for (int index = numOptions + 1; index < words.length; index++) {
         parameters.add(words[index]);
      }

      return parameters;
   }

   @Override
   public BatfishLogger getLogger() {
      return _logger;
   }

   private boolean getQuestion(List<String> parameters) {
      if (!isSetTestrig() || !isSetContainer(true)) {
         return false;
      }

      String questionName = parameters.get(0);

      String questionFileName = String.format("%s/%s/%s",
            BfConsts.RELPATH_QUESTIONS_DIR, questionName,
            BfConsts.RELPATH_QUESTION_FILE);

      String downloadedQuestionFile = _workHelper.getObject(_currContainerName,
            _currTestrig, questionFileName);
      if (downloadedQuestionFile == null) {
         _logger.errorf("Failed to get question file %s\n", questionFileName);
         return false;
      }

      String questionString = CommonUtil
            .readFile(Paths.get(downloadedQuestionFile));
      _logger.outputf("Question:\n%s\n", questionString);

      String paramsFileName = String.format("%s/%s/%s",
            BfConsts.RELPATH_QUESTIONS_DIR, questionName,
            BfConsts.RELPATH_QUESTION_PARAM_FILE);

      String downloadedParamsFile = _workHelper.getObject(_currContainerName,
            _currTestrig, paramsFileName);
      if (downloadedParamsFile == null) {
         _logger.errorf("Failed to get parameters file %s\n", paramsFileName);
         return false;
      }

      String paramsString = CommonUtil
            .readFile(Paths.get(downloadedParamsFile));
      _logger.outputf("Parameters:\n%s\n", paramsString);

      return true;
   }

   public Settings getSettings() {
      return _settings;
   }

   private boolean help(List<String> parameters) {
      if (parameters.size() == 1) {
         Command cmd = Command.fromName(parameters.get(0));
         printUsage(cmd);
      }
      else {
         printUsage();
      }
      return true;
   }

   private boolean initContainer(String[] words) {
      String containerPrefix = (words.length > 1) ? words[1]
            : DEFAULT_CONTAINER_PREFIX;
      _currContainerName = _workHelper.initContainer(containerPrefix);
      if (_currContainerName == null) {
         _logger.errorf("Could not init container\n");
         return false;
      }
      _logger.output("Active container is set");
      _logger.infof(" to  %s\n", _currContainerName);
      _logger.output("\n");
      return true;
   }

   private boolean initDeltaEnv(FileWriter outWriter, List<String> parameters)
         throws Exception {
      if (!isSetTestrig() || !isSetContainer(true)) {
         return false;
      }

      String deltaEnvLocation = parameters.get(0);
      String deltaEnvName = (parameters.size() > 1) ? parameters.get(1)
            : DEFAULT_DELTA_ENV_PREFIX + UUID.randomUUID().toString();

      if (!uploadTestrigOrEnv(deltaEnvLocation, deltaEnvName, false)) {
         return false;
      }

      _currDeltaEnv = deltaEnvName;
      _currDeltaTestrig = _currTestrig;

      _logger.output("Active delta testrig->environment is set");
      _logger.infof("to %s->%s\n", _currDeltaTestrig, _currDeltaEnv);
      _logger.output("\n");

      WorkItem wItemGenDdp = _workHelper.getWorkItemCompileDeltaEnvironment(
            _currContainerName, _currDeltaTestrig, _currEnv, _currDeltaEnv);

      if (!execute(wItemGenDdp, outWriter)) {
         return false;
      }

      return true;
   }

   private void initHelpers() {
      switch (_settings.getRunMode()) {
      case batch:
      case interactive:
         break;

      case gendatamodel:
      case genquestions:
      default:
         return;
      }

      String workMgr = _settings.getCoordinatorHost() + ":"
            + _settings.getCoordinatorWorkPort();
      String poolMgr = _settings.getCoordinatorHost() + ":"
            + _settings.getCoordinatorPoolPort();

      _workHelper = new BfCoordWorkHelper(workMgr, _logger, _settings);
      _poolHelper = new BfCoordPoolHelper(poolMgr);

      int numTries = 0;

      while (true) {
         try {
            numTries++;
            boolean exceededNumTriesWarningThreshold = numTries > NUM_TRIES_WARNING_THRESHOLD;
            if (_workHelper.isReachable(exceededNumTriesWarningThreshold)) {
               // print this message only we might have printed unable to
               // connect message earlier
               if (exceededNumTriesWarningThreshold) {
                  _logger.outputf("Connected to coordinator after %d tries\n",
                        numTries);
               }
               break;
            }
            Thread.sleep(1 * 1000); // 1 second
         }
         catch (Exception e) {
            _logger.errorf(
                  "Exeption while checking reachability to coordinator: ",
                  e.getMessage());
            System.exit(1);
         }
      }
   }

   private boolean initTestrig(FileWriter outWriter, List<String> parameters,
         boolean doDelta) throws Exception {
      String testrigLocation = parameters.get(0);
      String testrigName = (parameters.size() > 1) ? parameters.get(1)
            : DEFAULT_TESTRIG_PREFIX + UUID.randomUUID().toString();

      // initialize the container if it hasn't been init'd before
      if (!isSetContainer(false)) {
         _currContainerName = _workHelper
               .initContainer(DEFAULT_CONTAINER_PREFIX);
         if (_currContainerName == null) {
            _logger.errorf("Could not init container\n");
            return false;
         }
         _logger.outputf("Init'ed and set active container");
         _logger.infof(" to %s\n", _currContainerName);
         _logger.output("\n");
      }

      if (!uploadTestrigOrEnv(testrigLocation, testrigName, true)) {
         unsetTestrig(doDelta);
         return false;
      }

      _logger.output("Uploaded testrig. Parsing now.\n");

      WorkItem wItemParse = _workHelper.getWorkItemParse(_currContainerName,
            testrigName, false);

      if (!execute(wItemParse, outWriter)) {
         unsetTestrig(doDelta);
         return false;
      }

      if (!doDelta) {
         _currTestrig = testrigName;
         _currEnv = DEFAULT_ENV_NAME;
         _logger.infof("Base testrig is now %s\n", _currTestrig);
      }
      else {
         _currDeltaTestrig = testrigName;
         _currDeltaEnv = DEFAULT_ENV_NAME;
         _logger.infof("Delta testrig is now %s\n", _currDeltaTestrig);
      }

      return true;
   }

   private boolean isSetContainer(boolean printError) {
      if (!_settings.getSanityCheck()) {
         return true;
      }

      if (_currContainerName == null) {
         if (printError) {
            _logger.errorf("Active container is not set\n");
         }
         return false;
      }

      return true;
   }

   private boolean isSetDeltaEnvironment() {
      if (!_settings.getSanityCheck()) {
         return true;
      }

      if (_currDeltaTestrig == null) {
         _logger.errorf("Active delta testrig is not set\n");
         return false;
      }

      if (_currDeltaEnv == null) {
         _logger.errorf("Active delta environment is not set\n");
         return false;
      }
      return true;
   }

   private boolean isSetTestrig() {
      if (!_settings.getSanityCheck()) {
         return true;
      }

      if (_currTestrig == null) {
         _logger.errorf("Active testrig is not set.\n");
         _logger.errorf(
               "Specify testrig on command line (-%s <testrigdir>) or use command (%s <testrigdir>)\n",
               Settings.ARG_TESTRIG_DIR, Command.INIT_TESTRIG);
         return false;
      }
      return true;
   }

   private boolean listContainers() {
      String[] containerList = _workHelper.listContainers();
      _logger.outputf("Containers: %s\n", Arrays.toString(containerList));
      return true;
   }

   private boolean listEnvironments() {
      if (!isSetTestrig() || !isSetContainer(true)) {
         return false;
      }

      String[] environmentList = _workHelper
            .listEnvironments(_currContainerName, _currTestrig);
      _logger.outputf("Environments: %s\n", Arrays.toString(environmentList));

      return true;
   }

   private boolean listQuestions() {
      if (!isSetTestrig() || !isSetContainer(true)) {
         return false;
      }
      String[] questionList = _workHelper.listQuestions(_currContainerName,
            _currTestrig);
      _logger.outputf("Questions: %s\n", Arrays.toString(questionList));
      return true;
   }

   private boolean listTestrigs() {
      Map<String, String> testrigs = _workHelper
            .listTestrigs(_currContainerName);
      if (testrigs != null) {
         for (String testrigName : testrigs.keySet()) {
            _logger.outputf("Testrig: %s\n%s\n", testrigName,
                  testrigs.get(testrigName));
         }
      }
      return true;
   }

   private Map<String, String> parseParams(String paramsLine) {
      Map<String, String> parameters = new HashMap<>();

      Pattern pattern = Pattern.compile("([\\w_]+)\\s*=\\s*(.+)");

      String[] params = paramsLine.split("\\|");

      _logger.debugf("Found %d parameters\n", params.length);

      for (String param : params) {
         Matcher matcher = pattern.matcher(param);

         while (matcher.find()) {
            String key = matcher.group(1).trim();
            String value = matcher.group(2).trim();
            _logger.debugf("key=%s value=%s\n", key, value);

            parameters.put(key, value);
         }
      }

      return parameters;
   }

   private void printUsage() {
      for (Command cmd : Command.getUsageMap().keySet()) {
         printUsage(cmd);
      }
   }

   private void printUsage(Command command) {
      Pair<String, String> usage = Command.getUsageMap().get(command);
      _logger.outputf("%s %s\n\t%s\n\n", command.commandName(),
            usage.getFirst(), usage.getSecond());
   }

   private void printWorkStatusResponse(Pair<WorkStatusCode, String> response) {

      if (_logger.getLogLevel() >= BatfishLogger.LEVEL_INFO) {
         _logger.infof("status: %s\n", response.getFirst());

         BatfishObjectMapper mapper = new BatfishObjectMapper();
         Task task;
         try {
            task = mapper.readValue(response.getSecond(), Task.class);
         }
         catch (IOException e) {
            _logger.errorf("Could not deserliaze task object: %s\n", e);
            return;
         }

         if (task == null) {
            _logger.infof(".... null\n");
            return;
         }

         List<Batch> batches = task.getBatches();

         // when log level is INFO, we only print the last batch
         // else print all
         for (int i = 0; i < batches.size(); i++) {
            if (i == batches.size() - 1) {
               _logger.infof(".... %s\n", batches.get(i).toString());
            }
            else {
               _logger.debugf(".... %s\n", batches.get(i).toString());
            }
         }
      }
   }

   private boolean processCommand(String command) {
      String line = command.trim();
      if (line.length() == 0 || line.startsWith("#")) {
         return true;
      }
      _logger.debug("Doing command: " + line + "\n");
      String[] words = line.split("\\s+");
      if (words.length > 0) {
         if (!validCommandUsage(words)) {
            return false;
         }
      }
      return processCommand(words, null);
   }

   private boolean processCommand(String[] words, FileWriter outWriter) {
      try {
         List<String> options = getCommandOptions(words);
         List<String> parameters = getCommandParameters(words, options.size());

         Command command;
         try {
            command = Command.fromName(words[0]);
         }
         catch (BatfishException e) {
            _logger.errorf("Command failed: %s\n", e.getMessage());
            return false;
         }

         switch (command) {
         // this is a hidden command for testing

         // case "add-worker": {
         // boolean result = _poolHelper.addBatfishWorker(words[1]);
         // _logger.output("Result: " + result + "\n");
         // return true;
         // }
         case ADD_BATFISH_OPTION:
            return addBatfishOption(words, options, parameters);
         case ANSWER:
            return answer(words, outWriter, options, parameters);
         case ANSWER_DELTA:
            return answerDelta(words, outWriter, options, parameters);
         case CAT:
            return cat(words);
         case CHECK_API_KEY:
            return checkApiKey();
         case CLEAR_SCREEN:
            return clearScreen();
         case DEL_BATFISH_OPTION:
            return delBatfishOption(parameters);
         case DEL_CONTAINER:
            return delContainer(parameters);
         case DEL_ENVIRONMENT:
            return delEnvironment(parameters);
         case DEL_QUESTION:
            return delQuestion(parameters);
         case DEL_TESTRIG:
            return delTestrig(parameters);
         case DIR:
            return dir(parameters);
         case ECHO:
            return echo(words);
         case GEN_DP:
            return generateDataplane(outWriter);
         case GEN_DELTA_DP:
            return generateDeltaDataplane(outWriter);
         case GET:
            return get(words, outWriter, options, parameters, false);
         case GET_DELTA:
            return get(words, outWriter, options, parameters, true);
         case GET_ANSWER:
            return getAnswer(options, parameters);
         case GET_QUESTION:
            return getQuestion(parameters);
         case HELP:
            return help(parameters);
         case INIT_CONTAINER:
            return initContainer(words);
         case INIT_DELTA_ENV:
            return initDeltaEnv(outWriter, parameters);
         case INIT_DELTA_TESTRIG:
            return initTestrig(outWriter, parameters, true);
         case INIT_TESTRIG:
            return initTestrig(outWriter, parameters, false);
         case LIST_CONTAINERS:
            return listContainers();
         case LIST_ENVIRONMENTS:
            return listEnvironments();
         case LIST_QUESTIONS:
            return listQuestions();
         case LIST_TESTRIGS:
            return listTestrigs();
         case PROMPT:
            return prompt();
         case PWD:
            return pwd();
         case REINIT_DELTA_TESTRIG:
            return reinitTestrig(outWriter, true);
         case REINIT_TESTRIG:
            return reinitTestrig(outWriter, false);
         case SET_BATFISH_LOGLEVEL:
            return setBatfishLogLevel(parameters);
         case SET_CONTAINER:
            return setContainer(parameters);
         case SET_DELTA_ENV:
            return setDeltaEnv(parameters);
         case SET_ENV:
            return setEnv(parameters);
         case SET_DELTA_TESTRIG:
            return setDeltaTestrig(parameters);
         case SET_LOGLEVEL:
            return setLogLevel(parameters);
         case SET_PRETTY_PRINT:
            return setPrettyPrint(parameters);
         case SET_TESTRIG:
            return setTestrig(parameters);
         case SHOW_API_KEY:
            return showApiKey();
         case SHOW_BATFISH_LOGLEVEL:
            return showBatfishLogLevel();
         case SHOW_BATFISH_OPTIONS:
            return showBatfishOptions();
         case SHOW_CONTAINER:
            return showContainer();
         case SHOW_COORDINATOR_HOST:
            return showCoordinatorHost();
         case SHOW_DELTA_TESTRIG:
            return showDeltaTestrig();
         case SHOW_LOGLEVEL:
            return showLogLevel();
         case SHOW_TESTRIG:
            return showTestrig();
         case TEST:
            return test(parameters);
         case UPLOAD_CUSTOM_OBJECT:
            return uploadCustomObject(parameters);

         case EXIT:
         case QUIT:
            return exit();

         default:
            _logger.error("Unsupported command " + words[0] + "\n");
            _logger.error("Type 'help' to see the list of valid commands\n");
            return false;
         }
      }
      catch (Exception e) {
         e.printStackTrace();
         return false;
      }
   }

   private boolean processCommands(List<String> commands) {
      for (String command : commands) {
         if (!processCommand(command)) {
            return false;
         }
      }
      return true;
   }

   private boolean prompt() throws IOException {
      if (_settings.getRunMode() == RunMode.interactive) {
         _logger.output("\n\n[Press enter to proceed]\n\n");
         BufferedReader in = new BufferedReader(
               new InputStreamReader(System.in));
         in.readLine();
      }
      return true;
   }

   private boolean pwd() {
      final String dir = System.getProperty("user.dir");
      _logger.output("working directory = " + dir + "\n");
      return true;
   }

   private List<String> readCommands(Path startupFilePath) {
      List<String> commands = null;
      try {
         commands = Files.readAllLines(startupFilePath,
               StandardCharsets.US_ASCII);
      }
      catch (Exception e) {
         System.err.printf("Exception reading command file %s: %s\n",
               _settings.getBatchCommandFile(), e.getMessage());
         System.exit(1);
      }
      return commands;
   }

   private boolean reinitTestrig(FileWriter outWriter, boolean isDelta)
         throws Exception {
      String testrig;
      if (!isDelta) {
         _logger.output("Reinitializing testrig. Parsing now.\n");
         testrig = _currTestrig;
      }
      else {
         _logger.output("Reinitializing delta testrig. Parsing now.\n");
         testrig = _currDeltaTestrig;
      }

      WorkItem wItemParse = _workHelper.getWorkItemParse(_currContainerName,
            testrig, isDelta);

      if (!execute(wItemParse, outWriter)) {
         return false;
      }

      return true;
   }

   public void run(List<String> initialCommands) {
      loadPlugins();
      initHelpers();

      _logger.debugf("Will use coordinator at %s://%s\n",
            (_settings.getUseSsl()) ? "https" : "http",
            _settings.getCoordinatorHost());

      if (!processCommands(initialCommands)) {
         return;
      }

      // set container if specified
      if (_settings.getContainerId() != null) {
         if (!processCommand(Command.SET_CONTAINER.commandName() + "  "
               + _settings.getContainerId())) {
            return;
         }
      }

      // set testrig if dir or id is specified
      if (_settings.getTestrigDir() != null) {
         if (_settings.getTestrigId() != null) {
            System.err.println(
                  "org.batfish.client: Cannot supply both testrigDir and testrigId.");
            System.exit(1);
         }
         if (!processCommand(Command.INIT_TESTRIG.commandName() + " "
               + _settings.getTestrigDir())) {
            return;
         }
      }
      if (_settings.getTestrigId() != null) {
         if (!processCommand(Command.SET_TESTRIG.commandName() + "  "
               + _settings.getTestrigId())) {
            return;
         }
      }

      switch (_settings.getRunMode()) {

      case batch: {
         runBatchFile();
         break;
      }

      case gendatamodel:
         generateDatamodel();
         break;

      case genquestions:
         generateQuestions();
         break;

      case interactive: {
         runStartupFile();
         runInteractive();
         break;
      }

      default:
         System.err.println("org.batfish.client: Unknown run mode.");
         System.exit(1);
      }

   }

   private void runBatchFile() {
      Path batchCommandFilePath = Paths.get(_settings.getBatchCommandFile());
      List<String> commands = readCommands(batchCommandFilePath);
      boolean result = processCommands(commands);
      if (!result) {
         System.exit(1);
      }
   }

   private void runInteractive() {
      try {
         String rawLine;
         while (!_exit && (rawLine = _reader.readLine()) != null) {
            processCommand(rawLine);
         }
      }
      catch (Throwable t) {
         t.printStackTrace();
      }
      finally {
         FileHistory history = (FileHistory) _reader.getHistory();
         try {
            history.flush();
         }
         catch (IOException e) {
            e.printStackTrace();
         }
      }
   }

   private void runStartupFile() {
      Path startupFilePath = Paths.get(System.getenv(ENV_HOME), STARTUP_FILE);
      if (Files.exists(startupFilePath)) {
         List<String> commands = readCommands(startupFilePath);
         boolean result = processCommands(commands);
         if (!result) {
            System.exit(1);
         }
      }
   }

   private boolean setBatfishLogLevel(List<String> parameters) {
      String logLevelStr = parameters.get(0).toLowerCase();
      if (!BatfishLogger.isValidLogLevel(logLevelStr)) {
         _logger.errorf("Undefined loglevel value: %s\n", logLevelStr);
         return false;
      }
      _settings.setBatfishLogLevel(logLevelStr);
      _logger.output("Changed batfish loglevel to " + logLevelStr + "\n");
      return true;
   }

   private boolean setContainer(List<String> parameters) {
      _currContainerName = parameters.get(0);
      _logger.outputf("Active container is now set to %s\n",
            _currContainerName);
      return true;
   }

   private boolean setDeltaEnv(List<String> parameters) {
      _currDeltaEnv = parameters.get(0);
      if (_currDeltaTestrig == null) {
         _currDeltaTestrig = _currTestrig;
      }
      _logger.outputf("Active delta testrig->environment is now %s->%s\n",
            _currDeltaTestrig, _currDeltaEnv);
      return true;
   }

   private boolean setDeltaTestrig(List<String> parameters) {
      _currDeltaTestrig = parameters.get(0);
      _currDeltaEnv = (parameters.size() > 1) ? parameters.get(1)
            : DEFAULT_ENV_NAME;
      _logger.outputf("Delta testrig->env is now %s->%s\n", _currDeltaTestrig,
            _currDeltaEnv);
      return true;
   }

   private boolean setEnv(List<String> parameters) {
      if (!isSetTestrig()) {
         return false;
      }
      _currEnv = parameters.get(0);
      _logger.outputf("Base testrig->env is now %s->%s\n", _currTestrig,
            _currEnv);
      return true;
   }

   private boolean setLogLevel(List<String> parameters) {
      String logLevelStr = parameters.get(0).toLowerCase();
      if (!BatfishLogger.isValidLogLevel(logLevelStr)) {
         _logger.errorf("Undefined loglevel value: %s\n", logLevelStr);
         return false;
      }
      _logger.setLogLevel(logLevelStr);
      _settings.setLogLevel(logLevelStr);
      _logger.output("Changed client loglevel to " + logLevelStr + "\n");
      return true;
   }

   private boolean setPrettyPrint(List<String> parameters) {
      String ppStr = parameters.get(0).toLowerCase();
      boolean prettyPrint = Boolean.parseBoolean(ppStr);
      _settings.setPrettyPrintAnswers(prettyPrint);
      _logger.output("Set pretty printing answers to " + ppStr + "\n");
      return true;
   }

   private boolean setTestrig(List<String> parameters) {
      if (!isSetContainer(true)) {
         return false;
      }

      _currTestrig = parameters.get(0);
      _currEnv = (parameters.size() > 1) ? parameters.get(1) : DEFAULT_ENV_NAME;
      _logger.outputf("Base testrig->env is now %s->%s\n", _currTestrig,
            _currEnv);
      return true;
   }

   private boolean showApiKey() {
      _logger.outputf("Current API Key is %s\n", _settings.getApiKey());
      return true;
   }

   private boolean showBatfishLogLevel() {
      _logger.outputf("Current batfish log level is %s\n",
            _settings.getBatfishLogLevel());
      return true;
   }

   private boolean showBatfishOptions() {
      _logger.outputf("There are %d additional batfish options\n",
            _additionalBatfishOptions.size());
      for (String option : _additionalBatfishOptions.keySet()) {
         _logger.outputf("    %s : %s \n", option,
               _additionalBatfishOptions.get(option));
      }
      return true;
   }

   private boolean showContainer() {
      _logger.outputf("Current container is %s\n", _currContainerName);
      return true;
   }

   private boolean showCoordinatorHost() {
      _logger.outputf("Current coordinator host is %s\n",
            _settings.getCoordinatorHost());
      return true;
   }

   private boolean showDeltaTestrig() {
      if (!isSetDeltaEnvironment()) {
         return false;
      }
      _logger.outputf("Delta testrig->environment is %s->%s\n",
            _currDeltaTestrig, _currDeltaEnv);
      return true;
   }

   private boolean showLogLevel() {
      _logger.outputf("Current client log level is %s\n",
            _logger.getLogLevelStr());
      return true;
   }

   private boolean showTestrig() {
      if (!isSetTestrig()) {
         return false;
      }
      _logger.outputf("Base testrig->environment is %s->%s\n", _currTestrig,
            _currEnv);
      return true;
   }

   private boolean test(List<String> parameters) throws IOException {
      boolean failingTest = false;
      boolean missingReferenceFile = false;
      boolean testPassed = false;
      int testCommandIndex = 1;
      if (parameters.get(testCommandIndex).equals(FLAG_FAILING_TEST)) {
         testCommandIndex++;
         failingTest = true;
      }
      String referenceFileName = parameters.get(0);

      String[] testCommand = parameters
            .subList(testCommandIndex, parameters.size())
            .toArray(new String[0]);

      _logger.debugf("Ref file is %s. \n", referenceFileName,
            parameters.size());
      _logger.debugf("Test command is %s\n", Arrays.toString(testCommand));

      File referenceFile = new File(referenceFileName);

      if (!referenceFile.exists()) {
         _logger.errorf("Reference file does not exist: %s\n",
               referenceFileName);
         missingReferenceFile = true;
      }

      File testoutFile = Files.createTempFile("test", "out").toFile();
      testoutFile.deleteOnExit();

      FileWriter testoutWriter = new FileWriter(testoutFile);

      boolean testCommandSucceeded = processCommand(testCommand, testoutWriter);
      testoutWriter.close();

      if (!failingTest && testCommandSucceeded) {
         try {

            ObjectMapper mapper = new BatfishObjectMapper(
                  getCurrentClassLoader());

            // rewrite new answer string using local implementation
            String testOutput = CommonUtil
                  .readFile(Paths.get(testoutFile.getAbsolutePath()));

            Answer testAnswer = mapper.readValue(testOutput, Answer.class);
            String testAnswerString = mapper.writeValueAsString(testAnswer);

            if (!missingReferenceFile) {
               String referenceOutput = CommonUtil
                     .readFile(Paths.get(referenceFileName));

               // rewrite reference string using local implementation
               Answer referenceAnswer;
               try {
                  referenceAnswer = mapper.readValue(referenceOutput,
                        Answer.class);
               }
               catch (Exception e) {
                  throw new BatfishException(
                        "Error reading reference output using current schema (reference output is likely obsolete)",
                        e);
               }
               String referenceAnswerString = mapper
                     .writeValueAsString(referenceAnswer);

               // due to options chosen in BatfishObjectMapper, if json
               // outputs were equal, then strings should be equal

               if (referenceAnswerString.equals(testAnswerString)) {
                  testPassed = true;
               }
            }
         }
         catch (Exception e) {
            _logger.errorf("Exception in comparing test results: "
                  + ExceptionUtils.getStackTrace(e));
         }
      }
      else if (failingTest) {
         testPassed = !testCommandSucceeded;
      }

      StringBuilder sb = new StringBuilder();
      sb.append("'" + testCommand[0]);
      for (int i = 1; i < testCommand.length; i++) {
         sb.append(" " + testCommand[i]);
      }
      sb.append("'");
      String testCommandText = sb.toString();

      String message = "Test: " + testCommandText
            + (failingTest ? " results in error as expected"
                  : " matches " + referenceFileName)
            + (testPassed ? ": Pass\n" : ": Fail\n");

      _logger.output(message);
      if (!failingTest) {
         if (!testPassed) {
            String outFileName = referenceFile + ".testout";
            Files.move(Paths.get(testoutFile.getAbsolutePath()),
                  Paths.get(referenceFile + ".testout"),
                  StandardCopyOption.REPLACE_EXISTING);
            _logger.outputf("Copied output to %s\n", outFileName);
         }
      }
      return true;
   }

   private void unsetTestrig(boolean doDelta) {
      if (doDelta) {
         _currDeltaTestrig = null;
         _currDeltaEnv = null;
         _logger.info("Delta testrig and environment are now unset\n");
      }
      else {
         _currTestrig = null;
         _currEnv = null;
         _logger.info("Base testrig and environment are now unset\n");
      }
   }

   private boolean uploadCustomObject(List<String> parameters) {
      if (!isSetTestrig() || !isSetContainer(true)) {
         return false;
      }

      String objectName = parameters.get(0);
      String objectFile = parameters.get(1);

      // upload the object
      return _workHelper.uploadCustomObject(_currContainerName, _currTestrig,
            objectName, objectFile);
   }

   private boolean uploadTestrigOrEnv(String fileOrDir, String testrigOrEnvName,
         boolean isTestrig) throws Exception {

      File filePointer = new File(fileOrDir);

      String uploadFilename = fileOrDir;

      if (filePointer.isDirectory()) {
         File uploadFile = File.createTempFile("testrigOrEnv", "zip");
         uploadFile.deleteOnExit();
         uploadFilename = uploadFile.getAbsolutePath();
         ZipUtility.zipFiles(filePointer.getAbsolutePath(), uploadFilename);
      }

      boolean result = (isTestrig)
            ? _workHelper.uploadTestrig(_currContainerName, testrigOrEnvName,
                  uploadFilename)
            : _workHelper.uploadEnvironment(_currContainerName, _currTestrig,
                  testrigOrEnvName, uploadFilename);

      // unequal means we must have created a temporary file
      if (uploadFilename != fileOrDir) {
         new File(uploadFilename).delete();
      }

      return result;
   }

   private boolean validCommandUsage(String[] words) {
      return true;
   }

}
