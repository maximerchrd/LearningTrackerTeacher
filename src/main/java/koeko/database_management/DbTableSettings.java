package koeko.database_management;

import java.sql.*;
import java.util.ArrayList;

/**
 * Created by maximerichard on 24.11.17.
 */
public class DbTableSettings {
    static public int homeworkKeysLength = 10;
    static private String settingKey = "SETTING_KEY";
    static private String settingValue = "SETTING_VALUE";
    static private String nearbyModeKey = "NearbyMode";
    static private String correctionModeKey = "CorrectionMode";
    static private String forcedSyncKey = "ForcedSync";
    static private String uiModeKey = "UiMode";
    static private String teacherNameKey = "TeacherName";
    static private String homeworkKey = "HomeworkKey";

    static public void createTableSettings(Connection connection) {
        try {
            Statement statement = connection.createStatement();
            String sql = "CREATE TABLE IF NOT EXISTS settings " +
                    "(ID       INTEGER PRIMARY KEY AUTOINCREMENT," +
                    settingKey + " TEXT," +
                    settingValue + " TEXT) ";
            statement.executeUpdate(sql);
        } catch ( Exception e ) {
            e.printStackTrace();
            System.exit(0);
        }

        insertNewSetting(nearbyModeKey, "0");
        insertNewSetting(correctionModeKey, "0");
        insertNewSetting(forcedSyncKey, "0");
        insertNewSetting(uiModeKey, "0");
        insertNewSetting(teacherNameKey, "No Name");
    }

    static private void insertNewSetting(String newSettingKey, String newSettingValue) {
        String sql = "INSERT INTO settings (" + settingKey + "," + settingValue + ") VALUES(?,?)";
        DbUtils.updateWithTwoParam(sql, newSettingKey, newSettingValue);
    }

    static private Integer getIntegerSetting(String settingName) {
        Integer integerSetting;
        String query = "SELECT " + settingValue + " FROM settings WHERE " + settingKey + "=?";
        String stringSetting = DbUtils.getStringValueWithOneParam(query, settingName);
        try {
            integerSetting = Integer.valueOf(stringSetting);
        } catch (NumberFormatException e) {
            integerSetting = -1;
            e.printStackTrace();
        }
        return integerSetting;
    }

    static private String getStringSetting(String settingName) {
        String query = "SELECT " + settingValue + " FROM settings WHERE " + settingKey + "=?";
        String setting = DbUtils.getStringValueWithOneParam(query, settingName);
        return setting;
    }

    static public Integer getNearbyMode() {
        return getIntegerSetting(nearbyModeKey);
    }

    static public Integer getCorrectionMode() {
        return getIntegerSetting(correctionModeKey);
    }

    static public Integer getForceSync() {
        return getIntegerSetting(forcedSyncKey);
    }

    static public Integer getUIMode() {
        return getIntegerSetting(uiModeKey);
    }

    static public String getTeacherName() {
        return getStringSetting(teacherNameKey);
    }

    static public ArrayList<String> getHomeworkKeys() {
        String sql = "SELECT " + settingValue + " FROM settings WHERE " + settingKey + "=?";
        return DbUtils.getArrayStringWithOneParam(sql, homeworkKey);
    }

    static public void insertNearbyMode(Integer nearbyMode) {
        String sql = "UPDATE settings SET " + settingValue + " = ? WHERE " + settingKey + "=?";
        DbUtils.updateWithTwoParam(sql, nearbyMode.toString(), nearbyModeKey);
    }

    static public void insertHomeworkKey(String hwKey) {
        insertNewSetting(homeworkKey, hwKey);
    }

    static public void insertUIMode(Integer UIMode) {
        String sql = "UPDATE settings SET " + settingValue + " = ? WHERE " + settingKey + "=?";
        DbUtils.updateWithTwoParam(sql, UIMode.toString(), uiModeKey);
    }

    static public void insertCorrectionMode(Integer correctionMode) {
        String sql = "UPDATE settings SET " + settingValue + " = ? WHERE " + settingKey + "=?";
        DbUtils.updateWithTwoParam(sql, correctionMode.toString(), correctionModeKey);
    }

    static public void insertForceSync(Integer forceSync) {
        String sql = "UPDATE settings SET " + settingValue + " = ? WHERE " + settingKey + "=?";
        DbUtils.updateWithTwoParam(sql, forceSync.toString(), forcedSyncKey);
    }

    static public void insertTeacherName(String name) {
        String sql = "UPDATE settings SET " + settingValue + " = ? WHERE " + settingKey + "=?";
        DbUtils.updateWithTwoParam(sql, name, teacherNameKey);
    }
}
