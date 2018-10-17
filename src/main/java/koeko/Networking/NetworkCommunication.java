package koeko.Networking;

import koeko.Koeko;
import koeko.Tools.FilesHandler;
import koeko.controllers.LearningTrackerController;
import koeko.controllers.SettingsController;
import koeko.database_management.*;
import koeko.functionalTesting;
import koeko.questions_management.QuestionGeneric;
import koeko.questions_management.QuestionMultipleChoice;
import koeko.questions_management.QuestionShortAnswer;
import koeko.questions_management.Test;
import koeko.students_management.Classroom;
import koeko.students_management.Student;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * Created by maximerichard on 03/02/17.
 */
public class NetworkCommunication {
    static public NetworkCommunication networkCommunicationSingleton;
    private LearningTrackerController learningTrackerController = null;
    public Classroom aClass = null;

    private FileInputStream fis = null;
    private BufferedInputStream bis = null;
    private int network_solution = 0; //0: all devices connected to same wifi router
    final private int PORTNUMBER = 9090;
    private Vector<String> disconnectiongStudents;
    private Map<String, CopyOnWriteArrayList<String>> studentsToQuestionidsMap;
    public List<String> sentQuestionIds;
    private ArrayList<ArrayList<String>> questionIdsForGroups;
    private ArrayList<ArrayList<String>> studentNamesForGroups;


    public NetworkCommunication(LearningTrackerController learningTrackerController) {
        this.learningTrackerController = learningTrackerController;
        networkCommunicationSingleton = this;
        questionIdsForGroups = new ArrayList<>();
        studentNamesForGroups = new ArrayList<>();
        disconnectiongStudents = new Vector<>();
        studentsToQuestionidsMap = Collections.synchronizedMap(new LinkedHashMap<>());
        sentQuestionIds = new CopyOnWriteArrayList<>();
    }

    public LearningTrackerController getLearningTrackerController() {
        return learningTrackerController;
    }

    /**
     * starts the server
     *
     * @throws IOException
     */
    public void startServer() throws IOException {

        if (network_solution == 0) {
            //Send the ip of the computer to everyone on the subnet
            Thread sendIPthread = new Thread() {
                public void run() {
                    while (true) {
                        try {
                            sendIpAddress();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            };
            sendIPthread.start();


            // we create a server socket and bind it to port 9090.
            ServerSocket myServerSocket = new ServerSocket(PORTNUMBER);

            //Wait for client connection
            System.out.println("\nServer Started. Waiting for clients to connect...");
            aClass = Koeko.studentGroupsAndClass.get(0);
            Thread connectionthread = new Thread() {
                public void run() {
                    while (true) {
                        try {
                            //listening to client connection and accept it
                            Socket skt = myServerSocket.accept();
                            Student student = new Student();
                            student.setInetAddress(skt.getInetAddress());
                            student.setPort(String.valueOf(skt.getPort()));
                            System.out.println("Student with address: " + student.getInetAddress() + " accepted. Waiting for next client to connect");

                            try {
                                //register student
                                student.setInputStream(skt.getInputStream());
                                student.setOutputStream(skt.getOutputStream());
                                if (!aClass.studentAlreadyInClass(student)) {
                                    aClass.addStudentIfNotInClass(student);
                                    System.out.println("aClass.size() = " + aClass.getClassSize() + ". Added student: " + student.getInetAddress().toString());
                                } else {
                                    student.setInputStream(skt.getInputStream());
                                    student.setOutputStream(skt.getOutputStream());
                                    student = aClass.updateStudentStreams(student);
                                }


                                //send the active questions
                                ArrayList<String> activeIDs = (ArrayList<String>) Koeko.studentGroupsAndClass.get(0).getActiveIDs().clone();
                                if (activeIDs.size() > 0) {
                                    try {
                                        for (String activeID : activeIDs) {
                                            if (Long.valueOf(activeID) > 0) {
                                                sendMultipleChoiceWithID(activeID, student);
                                                sendShortAnswerQuestionWithID(activeID, student);
                                            } else {
                                                NetworkCommunication.networkCommunicationSingleton.sendTestWithID(QuestionGeneric.changeIdSign(activeID), student);
                                            }
                                        }
                                        System.out.println("address: " + student.getInetAddress());
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                }

                                //start a new thread for listening to each student
                                listenForClient(aClass.getStudents_vector().get(aClass.indexOfStudentWithAddress(student.getInetAddress().toString())));
                            } catch (IOException e1) {
                                e1.printStackTrace();
                            }
                        } catch (IOException e2) {
                            // TODO Auto-generated catch block
                            e2.printStackTrace();
                        }

                    }
                }
            };
            connectionthread.start();
        }
    }

    public void SendQuestionID(String QuestID, Vector<Student> students) {
        System.out.println("Sending question to " + students.size() + " students");
        //if question ID is negative (=test), change its sign
        if (Long.valueOf(QuestID) < 0) {
            QuestionGeneric.changeIdSign(QuestID);
        }

        for (int i = 0; i < students.size(); i++) {
            students.set(i, aClass.getStudentWithName(students.get(i).getName()));
        }


        for (Student student : students) {
            if (student.getOutputStream() != null) {
                byte[] idBytearraystring = new byte[80];
                String questIDString = "QID:MLT///" + String.valueOf(QuestID) + "///" + String.valueOf(SettingsController.correctionMode) + "///";
                byte[] prefixBytesArray = questIDString.getBytes(Charset.forName("UTF-8"));
                for (int i = 0; i < prefixBytesArray.length && i < 80; i++) {
                    idBytearraystring[i] = prefixBytesArray[i];
                }
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                try {
                    synchronized (outputStream) {
                        outputStream.write(idBytearraystring);
                        outputStream.flush();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }

                byte bytearraystring[] = outputStream.toByteArray();
                System.out.println("sending question: " + new String(bytearraystring) + " to single student");
                writeToOutputStream(student, bytearraystring);
            } else {
                System.out.println("Problem sending ID: outputstream is null; probably didnt receive acknowledgment of receipt on time");
            }
        }
    }

    public void sendMultipleChoiceWithID(String questionID, Student student) throws IOException {
        QuestionMultipleChoice questionMultipleChoice = null;
        questionMultipleChoice = DbTableQuestionMultipleChoice.getMultipleChoiceQuestionWithID(questionID);
        if (questionMultipleChoice.getQUESTION().length() > 0) {
            String question_text = questionMultipleChoice.getQUESTION() + "///";
            question_text += questionMultipleChoice.getOPT0() + "///";
            question_text += questionMultipleChoice.getOPT1() + "///";
            question_text += questionMultipleChoice.getOPT2() + "///";
            question_text += questionMultipleChoice.getOPT3() + "///";
            question_text += questionMultipleChoice.getOPT4() + "///";
            question_text += questionMultipleChoice.getOPT5() + "///";
            question_text += questionMultipleChoice.getOPT6() + "///";
            question_text += questionMultipleChoice.getOPT7() + "///";
            question_text += questionMultipleChoice.getOPT8() + "///";
            question_text += questionMultipleChoice.getOPT9() + "///";
            question_text += questionMultipleChoice.getID() + "///";
            question_text += questionMultipleChoice.getNB_CORRECT_ANS() + "///";
            Vector<String> subjectsVector = DbTableSubject.getSubjectsForQuestionID(questionID);
            int l = 0;
            for (l = 0; l < subjectsVector.size(); l++) {
                question_text += subjectsVector.get(l) + "|||";
            }
            if (l == 0) question_text += " ";
            question_text += "///";
            Vector<String> objectivesVector = DbTableLearningObjectives.getObjectiveForQuestionID(questionMultipleChoice.getID());
            for (l = 0; l < objectivesVector.size(); l++) {
                question_text += objectivesVector.get(l) + "|||";
            }
            if (l == 0) question_text += " ";
            question_text += "///";

            // send file : the sizes of the file and of the text are given in the first 80 bytes (separated by ":")
            int intfileLength = 0;
            File myFile = new File(questionMultipleChoice.getIMAGE());
            if (!questionMultipleChoice.getIMAGE().equals("none") && myFile.exists() && !myFile.isDirectory()) {
                question_text += questionMultipleChoice.getIMAGE().split("/")[questionMultipleChoice.getIMAGE().split("/").length - 1];
                intfileLength = (int) myFile.length();
            } else {
                question_text += questionMultipleChoice.getIMAGE() + "///";
            }

            //writing of the first 80 bytes
            byte[] bytearraytext = question_text.getBytes(Charset.forName("UTF-8"));
            int textbyteslength = bytearraytext.length;
            byte[] bytearray = new byte[80 + textbyteslength + intfileLength];
            String fileLength;
            fileLength = "MULTQ";
            fileLength += ":" + String.valueOf(intfileLength);
            fileLength += ":" + String.valueOf(textbyteslength) + ":";
            System.out.println("fileLength: " + fileLength);
            byte[] bytearraystring = fileLength.getBytes(Charset.forName("UTF-8"));
            for (int k = 0; k < bytearraystring.length; k++) {
                bytearray[k] = bytearraystring[k];
            }

            //copy the textbytes into the array which will be sent
            for (int k = 0; k < bytearraytext.length; k++) {
                bytearray[k + 80] = bytearraytext[k];
            }

            //write the file into the bytearray
            if (!questionMultipleChoice.getIMAGE().equals("none") && myFile.exists() && !myFile.isDirectory()) {
                fis = new FileInputStream(myFile);
                bis = new BufferedInputStream(fis);
                bis.read(bytearray, 80 + textbyteslength, intfileLength);
            }
            System.out.println("Sending " + questionMultipleChoice.getIMAGE() + "(" + intfileLength + " bytes)");
            int arraylength = bytearray.length;
            System.out.println("Sending " + arraylength + " bytes in total");
            writeToOutputStream(student, bytearray);

            sentQuestionIds.add(questionID);
        }
    }

    public void sendShortAnswerQuestionWithID(String questionID, Student student) throws IOException {
        QuestionShortAnswer questionShortAnswer = null;
        questionShortAnswer = DbTableQuestionShortAnswer.getShortAnswerQuestionWithId(questionID);
        if (questionShortAnswer.getQUESTION().length() > 0) {
            String question_text = questionShortAnswer.getQUESTION() + "///";
            question_text += questionShortAnswer.getID() + "///";

            //add answers
            ArrayList<String> answersArray = questionShortAnswer.getANSWER();
            for (int i = 0; i < answersArray.size(); i++) {
                question_text += answersArray.get(i) + "|||";
            }
            if (answersArray.size() == 0) question_text += " ";
            question_text += "///";

            //add subjects
            Vector<String> subjectsVector = DbTableSubject.getSubjectsForQuestionID(questionID);
            int l = 0;
            for (l = 0; l < subjectsVector.size(); l++) {
                question_text += subjectsVector.get(l) + "|||";
            }
            if (l == 0) question_text += " ";
            question_text += "///";

            //add objectives
            Vector<String> objectivesVector = DbTableLearningObjectives.getObjectiveForQuestionID(questionShortAnswer.getID());
            for (l = 0; l < objectivesVector.size(); l++) {
                question_text += objectivesVector.get(l) + "|||";
            }
            if (l == 0) question_text += " ";
            question_text += "///";

            // send file : the sizes of the file and of the text are given in the first 80 bytes (separated by ":")
            int intfileLength = 0;
            File myFile = new File(questionShortAnswer.getIMAGE());
            ;
            if (!questionShortAnswer.getIMAGE().equals("none") && myFile.exists() && !myFile.isDirectory()) {
                question_text += questionShortAnswer.getIMAGE().split("/")[questionShortAnswer.getIMAGE().split("/").length - 1];
                intfileLength = (int) myFile.length();
            } else {
                question_text += questionShortAnswer.getIMAGE() + "///";
            }

            //writing of the first 80 bytes
            byte[] bytearraytext = question_text.getBytes(Charset.forName("UTF-8"));
            int textbyteslength = bytearraytext.length;
            byte[] bytearray = new byte[80 + textbyteslength + intfileLength];
            String fileLength;
            fileLength = "SHRTA";
            fileLength += ":" + String.valueOf(intfileLength);
            fileLength += ":" + String.valueOf(textbyteslength) + ":";
            byte[] bytearraystring = fileLength.getBytes(Charset.forName("UTF-8"));
            for (int k = 0; k < bytearraystring.length; k++) {
                bytearray[k] = bytearraystring[k];
            }

            //copy the textbytes into the array which will be sent
            for (int k = 0; k < bytearraytext.length; k++) {
                bytearray[k + 80] = bytearraytext[k];
            }

            //write the file into the bytearray   !!! tested up to 630000 bytes, does not work with file of 4,7MB
            if (!questionShortAnswer.getIMAGE().equals("none") && myFile.exists() && !myFile.isDirectory()) {
                fis = new FileInputStream(myFile);
                bis = new BufferedInputStream(fis);
                bis.read(bytearray, 80 + textbyteslength, intfileLength);
            }
            System.out.println("Sending " + questionShortAnswer.getIMAGE() + "(" + intfileLength + " bytes)");
            int arraylength = bytearray.length;
            System.out.println("Sending " + arraylength + " bytes in total");
            writeToOutputStream(student, bytearray);

            sentQuestionIds.add(questionID);
        }
    }

    public ArrayList<String> sendTestWithID(String testID, Student student) {
        Test testToSend = new Test();
        try {
            testToSend = DbTableTest.getTestWithID(testID);
            testToSend.setMedalsInstructions(DbTableTest.getMedals(testToSend.getTestName()));
            byte[] bytesArray = DataConversion.testToBytesArray(testToSend);
            writeToOutputStream(student, bytesArray);
        } catch (Exception e) {
            e.printStackTrace();
        }

        //if the student is connecting (student!=null) then add the id to the deviceQuestions
        if (student != null) {
            if (Long.valueOf(testID) > 0) {
                testID = QuestionGeneric.changeIdSign(testID);
            }
            student.getDeviceQuestions().add(testID);
        }

        return testToSend.getIdsQuestions();
    }

    public void sendTestEvaluation(String studentName, String test, String objective, String evaluation) {
        Student student = aClass.getStudentWithName(studentName);
        if (student.getOutputStream() != null) {
            String testId = DbTableTest.getTestIdWithName(test);
            String objectiveId = DbTableLearningObjectives.getObjectiveIdFromName(objective);
            String toSend = testId + "///" + test + "///" + objectiveId + "///" + objective + "///" + evaluation + "///";
            System.out.println("Sending string: " + toSend);
            try {
                byte[] bytesArray = toSend.getBytes();
                String prefix = "OEVAL:" + bytesArray.length + "///";
                byte[] bytesPrefix = new byte[80];
                byte[] bytesPrefixString = prefix.getBytes();
                for (int i = 0; i < bytesPrefixString.length; i++) {
                    bytesPrefix[i] = bytesPrefixString[i];
                }
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                outputStream.write(bytesPrefix);
                outputStream.write(bytesArray);
                byte wholeBytesArray[] = outputStream.toByteArray();
                writeToOutputStream(student, wholeBytesArray);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * method that listen for the client data transfers
     *
     * @throws IOException
     */
    private void listenForClient(final Student arg_student) throws IOException {
        //for (int i = 0; i < aClass.getClassSize(); i++) {
        final InputStream answerInStream = arg_student.getInputStream();
        Thread listeningthread = new Thread() {
            public void run() {
                int bytesread = 0;
                Boolean ableToRead = true;
                while (bytesread >= 0 && ableToRead) {
                    try {
                        byte[] in_bytearray = new byte[1000];
                        bytesread = answerInStream.read(in_bytearray);
                        if (bytesread >= 1000) System.out.println("Answer too large for bytearray: " + bytesread + " bytes read");
                        if (bytesread >= 0) {
                            String answerString = new String(in_bytearray, 0, bytesread, "UTF-8");
                            System.out.println(arg_student.getName() + ":" + System.nanoTime() / 1000000000 + ":" + bytesread + "bytes:" + answerString);
                            if (answerString.split("///")[0].contains("ANSW")) {
                                //arg_student.setName(answerString.split("///")[2]);
                                double eval = DbTableIndividualQuestionForStudentResult.addIndividualQuestionForStudentResult(answerString.split("///")[5],
                                        answerString.split("///")[2], answerString.split("///")[3], answerString.split("///")[0]);
                                SendEvaluation(eval, answerString.split("///")[5], arg_student);

                                //find out to which group the student and answer belong
                                Integer groupIndex = -1;
                                String questID = answerString.split("///")[5];
                                for (int i = 0; i < Koeko.studentGroupsAndClass.size(); i++) {
                                    if (Koeko.studentGroupsAndClass.get(i).getOngoingQuestionsForStudent().get(arg_student.getName()) != null &&
                                            Koeko.studentGroupsAndClass.get(i).getOngoingQuestionsForStudent().get(arg_student.getName()).contains(String.valueOf(questID))) {
                                        groupIndex = i;
                                        Koeko.studentGroupsAndClass.get(i).getOngoingQuestionsForStudent().get(arg_student.getName())
                                                .remove(questID);
                                    }
                                }

                                if (groupIndex == -1) {
                                    groupIndex = 0;
                                    for (int i = 0; i < studentNamesForGroups.size(); i++) {
                                        if (studentNamesForGroups.get(i).contains(arg_student.getName()) && questionIdsForGroups.get(i).contains(questID)) {
                                            groupIndex = i;
                                            questionIdsForGroups.get(i).remove(questID);
                                        }
                                    }
                                }

                                learningTrackerController.addAnswerForUser(arg_student, answerString.split("///")[3], answerString.split("///")[4], eval,
                                        answerString.split("///")[5], groupIndex);
                                String nextQuestion = arg_student.getNextQuestionID(answerString.split("///")[5]);
                                System.out.println("student: " + arg_student.getName() + ";former question: " + questID + "; nextQuestion:" + nextQuestion);
                                for (String testid : arg_student.getTestQuestions()) {
                                    System.out.println(testid);
                                }
                                if (!nextQuestion.contentEquals("-1")) {
                                    Vector<Student> singleStudent = new Vector<>();
                                    singleStudent.add(arg_student);
                                    SendQuestionID(nextQuestion, singleStudent);
                                }

                                //set evaluation if question belongs to a test
                                if (arg_student.getActiveTest().getIdsQuestions().contains(questID)) {
                                    System.out.println("inserting question evaluation for test");
                                    int questionIndex = arg_student.getActiveTest().getIdsQuestions().indexOf(questID);
                                    if (questionIndex < arg_student.getActiveTest().getQuestionsEvaluations().size() && questionIndex >= 0) {
                                        arg_student.getActiveTest().getQuestionsEvaluations().set(questionIndex, eval);
                                    }
                                    Boolean testCompleted = true;
                                    for (Double questEval : arg_student.getActiveTest().getQuestionsEvaluations()) {
                                        if (questEval < 0) {
                                            testCompleted = false;
                                        }
                                    }
                                    if (testCompleted) {
                                        Double testEval = 0.0;
                                        for (Double questEval : arg_student.getActiveTest().getQuestionsEvaluations()) {
                                            testEval += questEval;
                                        }
                                        testEval = testEval / arg_student.getActiveTest().getQuestionsEvaluations().size();
                                        arg_student.getActiveTest().setTestEvaluation(testEval);
                                        DbTableIndividualQuestionForStudentResult.addIndividualTestEval(arg_student.getActiveTest().getIdTest(), arg_student.getName(), testEval);
                                    }
                                }
                            } else if (answerString.split("///")[0].contains("CONN")) {
                                ReceptionProtocol.receivedCONN(arg_student, answerString, aClass, studentsToQuestionidsMap);

                                //copy some basic informations because arg_student is used to write the answer into the table
                                Student.essentialCopyStudent(aClass.getStudentWithIP(arg_student.getInetAddress().toString()), arg_student);
                            } else if (answerString.split("///")[0].contains("DISC")) {
                                Student student = aClass.getStudentWithIP(arg_student.getInetAddress().toString());
                                student.setUniqueDeviceID(answerString.split("///")[1]);
                                student.setName(answerString.split("///")[2]);
                                student.setConnected(false);
                                if (answerString.contains("close-connection")) {
                                    learningTrackerController.userDisconnected(student);

                                    System.out.println("Student device really disconnecting. We should close the connection");
                                    arg_student.getOutputStream().flush();
                                    arg_student.getOutputStream().close();
                                    arg_student.setOutputStream(null);
                                    arg_student.getInputStream().close();
                                    arg_student.setInputStream(null);
                                    ableToRead = false;
                                } else if (answerString.contains("locked")) {
                                    disconnectiongStudents.remove(student.getUniqueDeviceID());
                                } else {
                                    disconnectiongStudents.add(student.getUniqueDeviceID());
                                    Thread studentDisconnectionQThread = new Thread() {
                                        public void run() {
                                            try {
                                                Thread.sleep(1000);
                                            } catch (InterruptedException e) {
                                                e.printStackTrace();
                                            }
                                            if (disconnectiongStudents.contains(student.getUniqueDeviceID())) {
                                                learningTrackerController.userDisconnected(student);
                                                disconnectiongStudents.remove(student.getUniqueDeviceID());
                                            }
                                        }
                                    };
                                    studentDisconnectionQThread.start();
                                }
                            } else if (answerString.split("///")[0].contains("OK")) {
                                ArrayList<String> receptionArray = new ArrayList<String>(Arrays.asList(answerString.split("///")));
                                for (String receivedString : receptionArray) {
                                    if (receivedString.matches("[0-9]+")) {
                                        if (!arg_student.getUniqueDeviceID().contentEquals("no identifier")) {
                                            studentsToQuestionidsMap.get(arg_student.getUniqueDeviceID()).add(receivedString);
                                            if (studentsToQuestionidsMap.get(arg_student.getUniqueDeviceID()).containsAll(sentQuestionIds)) {
                                                Koeko.studentsVsQuestionsTableControllerSingleton.setStatusQuestionsReceived(arg_student, 1);
                                            } else {
                                                Koeko.studentsVsQuestionsTableControllerSingleton.setStatusQuestionsReceived(arg_student, 0);
                                            }
                                        } else {
                                            System.out.println("WARNING: received OK but arg_student UniqueDeviceID was not initialized");
                                        }
                                    }
                                }
                            } else if (answerString.split("///")[0].contains("ACCUSERECEPTION")) {
                                int nbAccuses = answerString.split("ACCUSERECEPTION", -1).length - 1;
                                functionalTesting.nbAccuseReception += nbAccuses;
                                System.out.println(functionalTesting.nbAccuseReception);
                                if (functionalTesting.nbAccuseReception >= (functionalTesting.numberStudents * functionalTesting.numberOfQuestions)) {
                                    functionalTesting.endTimeQuestionSending = System.currentTimeMillis();
                                }
                            }
                        } else {

                        }
                    } catch (SocketException sockex) {
                        if (sockex.toString().contains("timed out")) {
                            System.out.println("Socket exception: read timed out");
                        } else if (sockex.toString().contains("Connection reset")) {
                            System.out.println("Socket exception: connection reset");
                        } else {
                            System.out.println("Other Socket exception");
                        }
                        bytesread = -1;
                    } catch (IOException e1) {
                        System.out.println("Some other IOException occured");
                        if (e1.toString().contains("Connection reset")) {
                            bytesread = -1;
                        }
                    }
                }
            }
        };
        listeningthread.start();
    }

    public void SendMediaFile(File mediaFile, Student student) {
        try {
            byte[] fileData = Files.readAllBytes(mediaFile.toPath());

            //build info prefix
            String[] mediaPathElements = mediaFile.toString().split(File.separator);
            String mediaName = mediaFile.toString();
            if (mediaPathElements.length > 0) {
                mediaName = mediaPathElements[mediaPathElements.length - 1];
                if (mediaName.length() > 14) {
                    mediaName = mediaName.substring(mediaName.length() - 14, mediaName.length());
                }
            }

            String infoString = "FILE///" + mediaName + "///" + fileData.length + "///";
            byte[] infoPrefix = new byte[80];
            for (int i = 0; i < infoPrefix.length && i < infoString.getBytes().length; i++) {
                infoPrefix[i] = infoString.getBytes()[i];
            }
            byte[] allData = Arrays.copyOf(infoPrefix, infoPrefix.length + fileData.length);
            System.arraycopy(fileData, 0, allData, infoPrefix.length, fileData.length);
            writeToOutputStream(student, allData);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void SendEvaluation(double evaluation, String questionID, Student student) {
        String evalToSend = "EVAL///" + evaluation + "///" + questionID + "///";
        System.out.println("sending: " + evalToSend);
        byte[] bytes = new byte[80];
        int bytes_length = 0;
        try {
            bytes_length = evalToSend.getBytes("UTF-8").length;
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        for (int i = 0; i < bytes_length; i++) {
            try {
                bytes[i] = evalToSend.getBytes("UTF-8")[i];
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }
        writeToOutputStream(student, bytes);

        /**
         * CODE USED FOR TESTING
         */
        if (functionalTesting.testMode) {
            functionalTesting.studentsNbEvalSent.put(student.getName(), functionalTesting.studentsNbEvalSent.get(student.getName()) + 1);
        }
    }

    public void UpdateEvaluation(double evaluation, String questionID, String studentID) {
        Student student = aClass.getStudentWithID(studentID);
        String evalToSend = "";

        evalToSend += "UPDEV///" + evaluation + "///" + questionID + "///";
        System.out.println("sending: " + evalToSend);
        byte[] bytes = new byte[80];
        int bytes_length = 0;
        try {
            bytes_length = evalToSend.getBytes("UTF-8").length;
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        for (int i = 0; i < bytes_length; i++) {
            try {
                bytes[i] = evalToSend.getBytes("UTF-8")[i];
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }
        writeToOutputStream(student, bytes);
    }

    public void SendCorrection(String questionID) {
        String messageToSend = "CORR///";
        messageToSend += String.valueOf(questionID) + "///" + UUID.randomUUID().toString().substring(0, 4) + "///";
        byte[] bytes = new byte[80];
        int bytes_length = 0;
        try {
            bytes_length = messageToSend.getBytes("UTF-8").length;
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        for (int i = 0; i < bytes_length; i++) {
            try {
                bytes[i] = messageToSend.getBytes("UTF-8")[i];
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }
        for (Student student : aClass.getStudents_vector()) {
            writeToOutputStream(student, bytes);
        }
    }

    public Classroom getClassroom() {
        return aClass;
    }

    public void removeQuestion(int index) {
        learningTrackerController.removeQuestion(index);
    }

    public void addQuestion(String question, String ID, Integer group) {
        learningTrackerController.addQuestion(question, ID, group);
    }

    public Boolean checkIfQuestionsOnDevices() {
        Boolean questionsOnDevices = true;
        for (Map.Entry<String, CopyOnWriteArrayList<String>> entry : studentsToQuestionidsMap.entrySet()) {
            CopyOnWriteArrayList<String> questions = entry.getValue();
            if (!questions.containsAll(sentQuestionIds)) {
                questionsOnDevices = false;
                break;
            }

            //for debug
            System.out.println("Student device: " + entry.getKey());
            for (String question : questions) {
                System.out.println("question: " + question);
            }
        }

        return questionsOnDevices;
    }

    public void activateTestForGroup(ArrayList<String> questionIds, ArrayList<String> students, String testID) {

        //first reinitialize if groups array are same size as number of groups (meaning we are in a new groups session)
        if (questionIdsForGroups.size() == Koeko.studentGroupsAndClass.size() - 1) {
            questionIdsForGroups.clear();
            studentNamesForGroups.clear();
        }
        //add ids(clone it because we want to remove its content later without affecting the source array) and students to group arrays
        questionIdsForGroups.add(new ArrayList<>());
        for (String id : questionIds) {
            questionIdsForGroups.get(questionIdsForGroups.size() - 1).add(id);
        }
        studentNamesForGroups.add(students);

        for (String studentName : students) {
            Student student = aClass.getStudentWithName(studentName);
            if (questionIds.size() > 0) {
                //get the first question ID which doesn't correspond to a test
                int i = 0;
                for (; i < questionIds.size() && Long.valueOf(questionIds.get(i)) < 0; i++) {
                }
                Vector<Student> singleStudent = new Vector<>();
                singleStudent.add(student);
                SendQuestionID(questionIds.get(i), singleStudent);
            }
            student.setTestQuestions((ArrayList<String>) questionIds.clone());

            //following code not used for now
            if (Long.valueOf(testID) != 0) {
                Test studentTest = new Test();
                studentTest.setIdTest(testID);
                studentTest.setIdsQuestions((ArrayList<String>) questionIds.clone());
                for (String ignored : questionIds) {
                    studentTest.getQuestionsEvaluations().add(-1.0);
                }
                student.setActiveTest(studentTest);
            }
        }
    }

    private void writeToOutputStream(Student student, byte[] bytearray) {
        Thread writingThread = new Thread() {
            public void run() {
                if (student == null) {
                    for (Student singleStudent : aClass.getStudents_vector()) {
                        if (singleStudent.getOutputStream() != null) {
                            try {
                                synchronized (singleStudent.getOutputStream()) {
                                    singleStudent.getOutputStream().write(bytearray, 0, bytearray.length);
                                    singleStudent.getOutputStream().flush();
                                }
                            } catch (SocketException sockex) {
                                System.out.println("SocketException (socket closed by client?)");
                            } catch (IOException ex2) {
                                if (ex2.toString().contains("Broken pipe")) {
                                    System.out.println("Broken pipe with a student (student was null)");
                                } else {
                                    System.out.println("Other IOException occured");
                                    ex2.printStackTrace();
                                }
                            } catch (NullPointerException nulex) {
                                System.out.println("NullPointerException in a thread with null output stream (closed by another thread)");
                            }
                        }
                    }
                } else {
                    try {
                        synchronized (student.getOutputStream()) {
                            student.getOutputStream().write(bytearray, 0, bytearray.length);
                            student.getOutputStream().flush();
                        }
                    } catch (SocketException sockex) {
                        System.out.println("SocketException (socket closed by client?)");
                    } catch (IOException ex2) {
                        if (ex2.toString().contains("Broken pipe")) {
                            System.out.println("Broken pipe with a student (student was null)");
                        } else {
                            System.out.println("Other IOException occured");
                            ex2.printStackTrace();
                        }
                    } catch (NullPointerException nulex) {
                        System.out.println("NullPointerException in a thread with null output stream (closed by another thread)");
                    }
                }
            }
        };
        writingThread.start();
    }

    public void popUpIfStudentIdentifierCollision(String studentName) {
        Platform.runLater(new Runnable() {
            @Override
            public void run() {
                final Stage dialog = new Stage();
                dialog.initModality(Modality.APPLICATION_MODAL);
                dialog.initOwner(Koeko.studentsVsQuestionsTableControllerSingleton);
                VBox dialogVbox = new VBox(20);
                dialogVbox.getChildren().add(new Text(studentName + " is trying to connect but has a different " +
                        "device identifier \n than the student with the same name already registered."));
                Scene dialogScene = new Scene(dialogVbox, 450, 40);
                dialog.setScene(dialogScene);
                dialog.show();
            }
        });
    }

    private void sendIpAddress() throws IOException {
        if (Koeko.questionBrowsingControllerSingleton != null &&
                Koeko.questionBrowsingControllerSingleton.ipAddresses != null &&
                Koeko.questionBrowsingControllerSingleton.ipAddresses.size() > 0) {
            for (int i = 0; i < Koeko.questionBrowsingControllerSingleton.ipAddresses.size(); i++) {
                DatagramSocket socket = new DatagramSocket();
                socket.setBroadcast(true);

                String stringAddress = Koeko.questionBrowsingControllerSingleton.ipAddresses.get(i);
                String stringBroadcastAddress = stringAddress.substring(0, stringAddress.lastIndexOf(".") + 1);
                stringBroadcastAddress = stringBroadcastAddress + "255";
                InetAddress address = InetAddress.getByName(stringBroadcastAddress);

                String message = "IPADDRESS///" + stringAddress + "///";
                byte[] buffer = message.getBytes();

                DatagramPacket packet
                        = new DatagramPacket(buffer, buffer.length, address, 9346);
                try {
                    socket.send(packet);
                } catch (IOException e) {
                    if (!e.toString().contains("is unreachable")) {
                        if (e.toString().contains("Network is down")) {
                            System.out.println("Trying to send ip through udp: Network is down");
                        } else if (e.toString().contains("Host is down")) {
                            System.out.println("Trying to send ip through udp: Host is down");
                        } else {
                            e.printStackTrace();
                        }
                    }
                }
                socket.close();
            }
        }

        try {
            Thread.sleep(700);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
