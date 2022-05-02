/*
* How to locally test this class and its method,
* mvn compile exec:java -Dexec.mainClass="org.mosip.dataprovider.test.CreatePersonaIDA"
* You should be in the mosipTestDataProvider folder, so that Maven can read the pom.xml file
*/

package org.mosip.dataprovider.test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.Set;

import org.javatuples.Pair;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.mosip.dataprovider.PacketTemplateProvider;
import org.mosip.dataprovider.models.DynamicFieldModel;
import org.mosip.dataprovider.models.MosipGenderModel;
import org.mosip.dataprovider.models.MosipIDSchema;
import org.mosip.dataprovider.models.MosipLocationModel;
import org.mosip.dataprovider.models.ResidentModel;
import org.mosip.dataprovider.models.MosipDocument;
import org.mosip.dataprovider.models.BiometricDataModel;
import org.mosip.dataprovider.models.SchemaValidator;
import org.mosip.dataprovider.preparation.MosipMasterData;
import org.mosip.dataprovider.util.CommonUtil;
import org.mosip.dataprovider.util.DataCallback;
import org.mosip.dataprovider.util.RestClient;
import org.mosip.dataprovider.util.Translator;
import org.mosip.dataprovider.BiometricDataProvider;

import org.mvel2.MVEL;

import io.cucumber.core.gherkin.messages.internal.gherkin.internal.com.eclipsesource.json.Json;
import variables.VariableManager;

import org.mosip.dataprovider.test.CreatePersona;
import java.io.IOException;
import org.slf4j.Logger;

public class CreatePersonaIDA {
    static Hashtable<Double, Properties> preregIDSchemaLatestVersion;

    /*
    * This method takes in as input an integer levelCode (which could represent city, province, etc.),
    * iterates over the locations HashTable and tries to find a location entity that has
    * the same hierarchyLevel as the specified levelCode, if found, it would return the name of the
    * hierarchy level, else it returns null
    */
    private static String getLocationLevel(int levelCode, Hashtable<String, MosipLocationModel> locations) {
        // Collect all the HashTable keys in e
        Enumeration<String> e = locations.keys();

        // Iterate over the keys of the HashTable
        while (e.hasMoreElements()) {
            String key = e.nextElement();
            MosipLocationModel mLocModel = locations.get(key);
            if (mLocModel.getHierarchyLevel() == levelCode)
                return key;
        }
        
        // If no matching levelCode is found then return null
        return null;
    }

    public static JSONObject createIdentityIDA(String residentFilePath, DataCallback cb) throws Exception {
        // Read the ResidentModel from the Persona file path which is given to the function
        ResidentModel resident = ResidentModel.readPersona(residentFilePath);

        Hashtable<Double, Properties> idSchemaLatestVersion = MosipMasterData.getIDSchemaLatestVersion();

        preregIDSchemaLatestVersion = MosipMasterData.getPreregIDSchemaLatestVersion();

        Double schemaVersion = preregIDSchemaLatestVersion.keys().nextElement();

        List<MosipIDSchema> schemaList = (List<MosipIDSchema>) preregIDSchemaLatestVersion.get(schemaVersion).get("schemaList");

        List<String> requiredAttributes = (List<String>) idSchemaLatestVersion.get(schemaVersion).get("requiredAttributes");
        JSONArray locationhierarchyArray = (JSONArray) preregIDSchemaLatestVersion.get(schemaVersion).get("locaitonherirachy");

        JSONObject identity = new JSONObject();

        Hashtable<String, MosipLocationModel> locations = resident.getLocation();
        Set<String> locationSet = locations.keySet();
        Hashtable<String, List<DynamicFieldModel>> dynaFields = resident.getDynaFields();
        Hashtable<String, List<MosipGenderModel>> genderTypes = resident.getGenderTypes();

        identity.put("IDSchemaVersion", schemaVersion);
        if (cb != null)
            cb.logDebug("createIdentityIDA:schemaVersion=" + schemaVersion);

        List<String> missingAttributes = resident.getMissAttributes();

        for (MosipIDSchema schemaItem : schemaList) {
            // Add log statement for current schemaItem
            if (cb != null)
                cb.logDebug(schemaItem.toJSONString());

            // Skip schemaItem if it doesn't exist in requiredAttributes
            if (!CommonUtil.isExists(requiredAttributes, schemaItem.getId()))
                continue;

            // Skip schemaItem if it is present in missingAttribute
            if (
                missingAttributes != null &&
                missingAttributes.stream().anyMatch(v -> v.equalsIgnoreCase(schemaItem.getId()))
            )
                continue;


            // Skip document and biometric type schemaItems
            if (
                schemaItem.getType() != null &&
                (
                    schemaItem.getType().equals("documentType") ||
                    schemaItem.getType().equals("biometricsType")
                )
            )
                continue;

            // Skip schemaItem if it's required attribute is not set to true
            if (!(schemaItem.getRequired()))
                continue;

            // Skip IDSchemaVersion schemaItem
            if (schemaItem.getId().equals("IDSchemaVersion"))
                continue;

            // Skip schemaItem if it has no Type attached to it
            if (schemaItem.getType() == null)
                continue;

            // Adding residentStatus to the identity JSON
            if (schemaItem.getFieldType().equals("dynamic") && schemaItem.getId().toLowerCase().contains("residen")) {
                String name = resident.getResidentStatus().getCode();

                CreatePersona.constructNode(
                    identity,
                    schemaItem.getId(),
                    resident.getPrimaryLanguage(),
                    resident.getSecondaryLanguage(),
                    name,
                    name,
                    schemaItem.getType().equals("simpleType") ? true : false
                );

                continue;
            }

            // Adding fullname to the identity JSON
            else if (schemaItem.getId().toLowerCase().equals("fullname")) {
                String name = resident.getName().getFirstName();

                if (resident.getName().getMidName() != null && !resident.getName().getMidName().equals(""))
                    name += " " + resident.getName().getMidName();

                name += " " + resident.getName().getSurName();
                name = name.trim();

                String name_sec = "";
                if (resident.getSecondaryLanguage() != null) {
                    name_sec = resident.getName_seclang().getFirstName();

                    if (
                        resident.getName_seclang().getMidName() != null &&
                            !resident.getName_seclang().getMidName().equals("")
                    )
                        name_sec += " " + resident.getName_seclang().getMidName();

                    name_sec += " " + resident.getName_seclang().getSurName();
                    name_sec = name_sec.trim();
                }

                CreatePersona.constructNode(
                    identity,
                    schemaItem.getId(),
                    resident.getPrimaryLanguage(),
                    resident.getSecondaryLanguage(),
                    name,
                    name_sec,
                    schemaItem.getType().equals("simpleType") ? true : false
                );

                continue;
            }

            // It is possible that fullname wasn't present, but firstname, lastname or middlename are present
            // So this else-if block adds these parameters to identity JSON
            else if (
                schemaItem.getId().toLowerCase().equals("firstname") ||
                schemaItem.getId().toLowerCase().equals("lastname") ||
                schemaItem.getId().toLowerCase().equals("middlename")
            ) {
                String name = "";
                String name_sec = "";

                if (schemaItem.getId().toLowerCase().equals("firstname")) {
                    name = resident.getName().getFirstName();
                    if (resident.getSecondaryLanguage() != null)
                        name_sec = resident.getName_seclang().getFirstName();
                }

                else if (schemaItem.getId().toLowerCase().equals("lastname")) {
                    name = resident.getName().getSurName();
                    if (resident.getSecondaryLanguage() != null)
                        name_sec = resident.getName_seclang().getSurName();
                }

                else if (schemaItem.getId().toLowerCase().equals("middlename")) {
                    name = resident.getName().getMidName();
                    if (resident.getSecondaryLanguage() != null)
                        name_sec = resident.getName_seclang().getMidName();
                }

                CreatePersona.constructNode(
                    identity,
                    schemaItem.getId(),
                    resident.getPrimaryLanguage(),
                    resident.getSecondaryLanguage(),
                    name,
                    name_sec,
                    schemaItem.getType().equals("simpleType") ? true : false
                );

                continue;
            }

            // Adding dateOfBirth or dob or birthdate to identity JSON
            else if (
                schemaItem.getId().toLowerCase().equals("dateofbirth") ||
                schemaItem.getId().toLowerCase().equals("dob") ||
                schemaItem.getId().toLowerCase().equals("birthdate")
            ) {
                String strDate = resident.getDob();
                CreatePersona.constructNode(
                    identity,
                    schemaItem.getId(),
                    resident.getPrimaryLanguage(),
                    resident.getSecondaryLanguage(),
                    strDate,
                    strDate,
                    schemaItem.getType().equals("simpleType") ? true : false
                );

                continue;
            }

            // Adding gender to identity JSON
            else if (schemaItem.getId().toLowerCase().equals("gender")) {
                String primaryValue = "Female";
                if (resident.getGender().equals("Male"))
                    primaryValue = "Male";

                String secValue = primaryValue;

                CreatePersona.constructNode(
                    identity,
                    schemaItem.getId(),
                    resident.getPrimaryLanguage(),
                    resident.getSecondaryLanguage(),
                    primaryValue,
                    secValue,
                    schemaItem.getType().equals("simpleType") ? true : false
                );

                continue;
            }

            // Adding address to identity JSON
            else if (schemaItem.getId().toLowerCase().contains("address")) {
                Pair<String, String> addrLines = PacketTemplateProvider.processAddresslines(
                  schemaItem, resident, identity
                );

                CreatePersona.constructNode(
                    identity,
                    schemaItem.getId(),
                    resident.getPrimaryLanguage(),
                    resident.getSecondaryLanguage(),
                    addrLines.getValue0(),
                    addrLines.getValue1(),
                    schemaItem.getType().equals("simpleType") ? true : false
                );

                continue;
            }

            // Adding email to identity JSON
            else if (
                schemaItem.getId().toLowerCase().contains("email") ||
                schemaItem.getId().toLowerCase().contains("mail")
            ) {
                String emailId = resident.getContact().getEmailId();

                CreatePersona.constructNode(
                    identity,
                    schemaItem.getId(),
                    resident.getPrimaryLanguage(),
                    resident.getSecondaryLanguage(),
                    emailId,
                    emailId,
                    schemaItem.getType().equals("simpleType") ? true : false
                );
                continue;
            }

            // Adding phone number to identity JSON
            else if (
                schemaItem.getId().toLowerCase().contains("phone") ||
                schemaItem.getId().toLowerCase().contains("mobile")
            ) {
                String mobileNo = resident.getContact().getMobileNumber();

                CreatePersona.constructNode(
                    identity,
                    schemaItem.getId(),
                    resident.getPrimaryLanguage(),
                    resident.getSecondaryLanguage(),
                    mobileNo,
                    mobileNo,
                    schemaItem.getType().equals("simpleType") ? true : false
                );

                continue;
            }

            // Adding referenceIdentityNumber to identity JSON
            else if (schemaItem.getId().toLowerCase().contains("referenceidentity")) {
                String id = resident.getId();

                CreatePersona.constructNode(
                  identity,
                  schemaItem.getId(),
                  resident.getPrimaryLanguage(),
                  resident.getSecondaryLanguage(),
                  id,
                  id,
                  schemaItem.getType().equals("simpleType") ? true : false
                );

                continue;
            }

            // Adding Location Hierarchy related parameters to identity JSON which include,
            // placeOfBirth, listCountry, region, province, city, postalCode, zone,
            else {
                for (int i = 0; i < locationhierarchyArray.length(); i++) {
                    JSONArray jsonArray = locationhierarchyArray.getJSONArray(i);

                    for (int j = 0; j < jsonArray.length(); j++) {
                        String id = jsonArray.getString(j);

                        if (schemaItem.getId().toLowerCase().equals(id.toLowerCase())) {
                            //String locLevel = (String) locationSet.toArray()[j];
                            String locLevel = getLocationLevel(j + 1, locations);

                            CreatePersona.constructNode(
                                identity,
                                schemaItem.getId(),
                                resident.getPrimaryLanguage(),
                                resident.getSecondaryLanguage(),
                                locations.get(locLevel).getCode(),
                                locations.get(locLevel).getCode(),
                                schemaItem.getType().equals("simpleType") ? true : false
                            );

                            break;
                        }
                    }
                    continue;
                }
            }
        }

        // If resident has no UIN, then set UIN to null in identity JSON
        // Otherwise place the actual UIN in identity
        String UIN = "null";
        if (resident.getUIN() != null)
            UIN = resident.getUIN();
        identity.put("UIN", UIN);

        // Adding biometrics to identity JSON

        // First convert the BiometricDataModel to an XML file with the help of the toCBEFF method
        // This method requires bioFilter list which contains the biometric elements which need to be extracted
        // We need all available elements, so we've specified all of them in the below list
        BiometricDataModel biometric = resident.getBiometric();
        List<String> bioFilter = Arrays.asList(
          "Face", "leftEye", "rightEye",
          "leftThumb", "leftIndex", "leftMiddle", "leftRing", "leftLittle",
          "rightThumb", "rightIndex", "rightMiddle", "rightRing", "rightLittle"
        );

        // The XML file will be saved as resident.id + "_biometrics.xml"
        BiometricDataProvider.toCBEFF(bioFilter, biometric, resident.getId() + "_biometrics.xml");
        JSONObject biometricsJSON = new JSONObject();
        biometricsJSON.put("format", "cbeff");

        // Pasting value of serialVersionUID parameter of biometricDataModel, i.e. 1L for "version" key
        biometricsJSON.put("version", 1L);

        // Note that we're only placing the file name of the biometrics XML file in here
        // This should probably be replaced by something more sensible later
        biometricsJSON.put("value", resident.getId() + "_biometrics.xml");
        identity.put("individualBiometrics", biometricsJSON);

        // Adding the introducer parameters to identity JSON
        // These are only valid if resident has a guardian, i.e. resident is a minor

        // introducerUIN = "null", if there's no introducer or no introducer UIN
        String introducerUIN = "null";
        if (resident.getGuardian() != null && resident.getGuardian().getUIN() != null)
            introducerUIN = resident.getGuardian().getUIN();
        identity.put("introducerUIN", introducerUIN);

        // Same as introducerUIN
        String introducerRID = "null";
        if (resident.getGuardian() != null && resident.getGuardian().getRID() != null)
            introducerRID = resident.getGuardian().getRID();
        identity.put("introducerRID", introducerRID);

        // Guardian's name, i.e. introducerName should be filled in the simpleType fashion that was followed in
        // the schemaItem for loop and that's why we use the constructNode() method here along with the same logic
        // that we used for fullname parameter in the for loop
        if (resident.getGuardian() != null && resident.getGuardian().getName() != null) {
            ResidentModel guardian = resident.getGuardian();
            String name = guardian.getName().getFirstName();

            if (guardian.getName().getMidName() != null && !guardian.getName().getMidName().equals(""))
                name += " " + guardian.getName().getMidName();

            name += " " + guardian.getName().getSurName();
            name = name.trim();

            String name_sec = "";
            if (guardian.getSecondaryLanguage() != null) {
                name_sec = guardian.getName_seclang().getFirstName();

                if (
                  guardian.getName_seclang().getMidName() != null &&
                  !guardian.getName_seclang().getMidName().equals("")
                )
                    name_sec += " " + guardian.getName_seclang().getMidName();

                name_sec += " " + guardian.getName_seclang().getSurName();
                name_sec = name_sec.trim();
            }

            CreatePersona.constructNode(
              identity,
              "introducerName",
              resident.getPrimaryLanguage(),
              resident.getSecondaryLanguage(),
              name,
              name_sec,
              true
            );
        }
        else
            identity.put("introducerName", "null");

        // Creating final output JSON "response" as per required format
        JSONObject response = new JSONObject();
        response.put("id", "{{id}}");

        JSONObject request = new JSONObject();
        request.put("registrationId", "{{registrationId}}");
        request.put("biometricReferenceId", "{{biometricReferenceId}}");
        request.put("identity", identity);

        // Attaching the documents list to request JSON
        List<JSONObject> documentList = new ArrayList<>();
        JSONObject curDocumentJSON = new JSONObject();

        // Iterate through each document stored in the resident
        for (int i=0; i<resident.getDocuments().size(); i++) {
            MosipDocument curDocument = resident.getDocuments().get(i);

            // Attach the docCategoryName parameter as category and docs parameter as value in a JSONObject
            curDocumentJSON.put("category", curDocument.getDocCategoryName());
            curDocumentJSON.put("value", curDocument.getDocs());

            // And attach the JSONObject to this list
            documentList.add(curDocumentJSON);
        }

        // The documentsList is then finally added to request JSON under the documents field
        request.put("documents", documentList);

        response.put("request", request);

        response.put("requesttime", "{{requesttime}}");
        response.put("version", "{{version}}");

        return response;
    }

//    public static void main(String[] args) {
//        try {
//            CreatePersonaIDA createPersonaIDA = new CreatePersonaIDA();
//            createPersonaIDA.createIdentityIDA("C:\\Work\\MOSIP\\residents\\residents_647989526968853933\\4006052100.json", null);
//        }
//        catch (IOException e) {
//            System.out.println("IO Exception");
//        }
//
//    }
}