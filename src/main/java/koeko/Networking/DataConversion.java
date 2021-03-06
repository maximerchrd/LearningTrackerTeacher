package koeko.Networking;

// import QuestionSendingController;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import koeko.controllers.SettingsController;
import koeko.database_management.DbTableLearningObjectives;
import koeko.database_management.DbTableRelationQuestionQuestion;
import koeko.database_management.DbTableSubject;
import koeko.questions_management.Test;
import koeko.view.SubjectsAndObjectivesForQuestion;
import koeko.view.TestView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;

public class DataConversion {
    static public byte[] testEvalToBytesArray(String evalToSend) {
        byte[] bytearraytesteval = evalToSend.getBytes();
        String textDataSize = String.valueOf(bytearraytesteval.length);
        String prefix = "OEVAL:" + textDataSize + "///";
        byte[] byteArrayPrefix = prefix.getBytes();
        byte[] wholeByteArray = new byte[80];
        for (int i = 0; i < byteArrayPrefix.length; i++) {
            wholeByteArray[i] = byteArrayPrefix[i];
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            synchronized (outputStream) {
                outputStream.write(wholeByteArray);
                outputStream.write(bytearraytesteval);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        wholeByteArray = outputStream.toByteArray( );

        return  wholeByteArray;
    }

    static public byte[] questionsSetToBytesArray(ArrayList<Integer> questionIDs, Integer testMode) {
        String testString = "";

        for (Integer questionId : questionIDs) {
            testString += String.valueOf(questionId) + "///";
        }

        byte[] bytearraytest = testString.getBytes();
        String textDataSize = String.valueOf(bytearraytest.length);
        String prefix = "TESYN:" + textDataSize + ":" + SettingsController.correctionMode + ":" + testMode +  "///";
        byte[] byteArrayPrefix = prefix.getBytes();
        byte[] wholeByteArray = new byte[80];
        for (int i = 0; i < byteArrayPrefix.length; i++) {
            wholeByteArray[i] = byteArrayPrefix[i];
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream( );
        try {
            synchronized (outputStream) {
                outputStream.write(wholeByteArray);
                outputStream.write(bytearraytest);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        wholeByteArray = outputStream.toByteArray( );

        return  wholeByteArray;
    }

    public static byte[] getBytesSubNObjForQuestion(String questionID) {
        ArrayList<String> subjects = new ArrayList<>(DbTableSubject.getSubjectsForQuestionID(questionID));
        ArrayList<String> objectives = new ArrayList<>(DbTableLearningObjectives.getObjectiveForQuestionID(questionID));
        String[] subjectsAr = new String[subjects.size()];
        String[] objectivesAr = new String[objectives.size()];
        subjectsAr = subjects.toArray(subjectsAr);
        objectivesAr = objectives.toArray(objectivesAr);
        SubjectsAndObjectivesForQuestion subNObj = new SubjectsAndObjectivesForQuestion(objectivesAr, subjectsAr, questionID);
        ObjectMapper mapper = new ObjectMapper();
        String jsonString = "";
        try {
            jsonString = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(subNObj);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }

        byte[] dataBytes = jsonString.getBytes();
        byte[] preprefix = new DataPrefix(DataPref.subObj,String.valueOf(dataBytes.length),"","").parseToString().getBytes();
        byte[] prefixBytes = new byte[NetworkCommunication.prefixSize];
        for (int i = 0; i < preprefix.length && i < NetworkCommunication.prefixSize; i++) {
            prefixBytes[i] = preprefix[i];
        }
        byte[] wholeBytesArray = Arrays.copyOf(prefixBytes, prefixBytes.length + dataBytes.length);
        System.arraycopy(dataBytes, 0, wholeBytesArray, prefixBytes.length, dataBytes.length);
        return wholeBytesArray;
    }
}
