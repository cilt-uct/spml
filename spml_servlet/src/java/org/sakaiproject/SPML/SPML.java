/**********************************************************************************
 * $URL: $
 * $Id: $
 ***********************************************************************************
 
Copyright (c) 2014 University of Cape Town

Licensed under the Educational Community License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

            http://opensource.org/licenses/ecl2

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

 **********************************************************************************/

/***
 * <p>
 * An implementation of an SPML client that creates user accounts and populates profiles
 * </p>
 * 
 * @author David Horwitz, University of Cape Town
 * @version $Revision: 3197 $
 */
package org.sakaiproject.SPML;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.validator.routines.EmailValidator;
import org.openspml.client.SOAPClient;
import org.openspml.client.SOAPMonitor;
import org.openspml.message.AddRequest;
import org.openspml.message.BatchRequest;
import org.openspml.message.DeleteRequest;
import org.openspml.message.ModifyRequest;
import org.openspml.message.SpmlRequest;
import org.openspml.message.SpmlResponse;
import org.openspml.server.SpmlHandler;
import org.openspml.util.SpmlBuffer;
import org.openspml.util.SpmlException;

import org.sakaiproject.api.common.edu.person.SakaiPerson;
import org.sakaiproject.api.common.edu.person.SakaiPersonManager;
import org.sakaiproject.api.common.type.Type;
import org.sakaiproject.authz.api.SecurityAdvisor;
import org.sakaiproject.authz.api.SecurityService;
import org.sakaiproject.component.cover.ComponentManager;
import org.sakaiproject.component.cover.ServerConfigurationService;
import org.sakaiproject.coursemanagement.api.AcademicSession;
import org.sakaiproject.coursemanagement.api.CourseManagementAdministration;
import org.sakaiproject.coursemanagement.api.CourseManagementService;
import org.sakaiproject.coursemanagement.api.CourseOffering;
import org.sakaiproject.coursemanagement.api.EnrollmentSet;
import org.sakaiproject.coursemanagement.api.Section;
import org.sakaiproject.coursemanagement.api.exception.IdNotFoundException;
import org.sakaiproject.db.api.SqlService;
import org.sakaiproject.email.api.EmailService;
import org.sakaiproject.emailtemplateservice.model.EmailTemplate;
import org.sakaiproject.emailtemplateservice.service.EmailTemplateService;
import org.sakaiproject.entity.api.ResourceProperties;
import org.sakaiproject.entity.api.ResourcePropertiesEdit;
import org.sakaiproject.event.api.UsageSession;
import org.sakaiproject.event.api.UsageSessionService;
import org.sakaiproject.sms.logic.external.NumberRoutingHelper;
import org.sakaiproject.tool.api.Session;
import org.sakaiproject.tool.api.SessionManager;
import org.sakaiproject.user.api.User;
import org.sakaiproject.user.api.UserAlreadyDefinedException;
import org.sakaiproject.user.api.UserDirectoryService;
import org.sakaiproject.user.api.UserEdit;
import org.sakaiproject.user.api.UserIdInvalidException;
import org.sakaiproject.user.api.UserLockedException;
import org.sakaiproject.user.api.UserNotDefinedException;
import org.sakaiproject.user.api.UserPermissionException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SPML implements SpmlHandler  {

	// Database table names
	private static final String TBL_UPDATED_USERS = "SPML_UPDATED_USERS";
	private static final String TBL_SPML_LOG = "spml_log";
	private static final String TBL_DEPT_LOOKUP = "UCT_ORG";
	
	// Sakai user properties
	private static final String PROP_SPML_LAST_UPDATE = "spml_last_update";
	private static final String PROP_DEACTIVATED = "SPML_DEACTIVATED";
	private static final String PROP_SENT_EMAIL = "uctNewMailSent";
	private static final String PROP_DATA_CLEARED_LAST = "data_cleared_last";
	private static final String PROP_CONTENT_REMOVED = "workspace_content_removed";
	
	// Attribute mappings to map SPML attributes to Sakai attributes
	private static final String FIELD_CN = "CN";
	private static final String FIELD_SURNAME = "Surname";
	private static final String FIELD_GN = "Given Name";
	private static final String FIELD_PN = "preferredName";
	private static final String FIELD_MAIL = "Email";
	private static final String FIELD_TYPE = "eduPersonPrimaryAffiliation";
	private static final String FIELD_FACULTY ="uctFaculty";
	private static final String FIELD_MOBILE = "mobile";
	private static final String FIELD_COURSES = "uctCourseCode";
	private static final String FIELD_PROGRAM = "uctProgramCode";
	private static final String FIELD_OU = "OU";
	private static final String FIELD_DOB = "DOB";
	private static final String FIELD_RES_CODE ="uctResidenceCode";
	private static final String FIELD_ORG_DECR = "uctorgaffiliation";
	private static final String FIELD_TITLE = "uctPersonalTitle";
	private static final String FIELD_ACADEMIC_CAREER = "uctAcademicCareer";		// not in use

	// User account types
	private static final String TYPE_OFFER = "offer";
	private static final String TYPE_STUDENT = "student";
	private static final String TYPE_STAFF = "staff";
	private static final String TYPE_THIRDPARTY = "thirdparty";

	// Course management set categories
	private static final String CAT_RESIDENCE = "residence";
	private static final String CAT_DEGREE = "degree";
	private static final String CAT_DEPT = "Department";
	private static final String CAT_COURSE = "course";
		
	// SPML user status
	private static final String STATUS_ACTIVE = "Active";
	private static final String STATUS_INACTIVE = "Inactive";
	private static final String STATUS_ADMITTED = "Admitted";

	// Special OFFER term (note: upper case)
	private static final String TERM_OFFER = "OFFER";

	// Auth details 
	private static final String spmlUser = ServerConfigurationService.getString("spml.user", "nobody");

	/**
	 * Use one of these to manage the basic SOAP communications.	
	 */
	SOAPClient _client;

	/**
	 * The "SOAP action" name.  If you are using the Apache SOAP router	
	 * this is used to dispatch the request.  If you are using the
	 * OpenSPML router, it is not required.
	 */
	String _action;

	/**
	 * Buffer we use to format the XML messages.
	 */
	SpmlBuffer _buffer;

	/**
	 * Trace flag.      
	 */
	boolean _trace;
	boolean _autoAction;

	int i;
	/**
	 * Set the "SOAP action name" which may be required by
	 * the SOAP router in the application server.
	 */
	public void setAction(String action) {
		_action = action;
	}


	/**
	 * Set an optional SOAP Header.  
	 * The string is expected to contain well formed XML.
	 */
	public void setHeader(String s) {
		_client.setHeader(s);
	}

	/**
	 * Set an optional list of attributes for the SOAP Body.
	 * The string is expected to contain a fragment of well formed
	 * XML attribute definitions, "wsu:Id='myBody'"
	 * It is assumed for now that any namespace references in the 
	 * attribute names do not need to be formally declared in
	 * the soap:Envelope.
	 */
	public void setBodyAttributes(String s) {	
		_client.setBodyAttributes(s);
	}

	public void setTrace(boolean b) {
		_trace = b;
	}

	/**
	 * Install a SOAP message monitor. 
	 */
	public void setMonitor(SOAPMonitor m) {
		_client.setMonitor(m);
	}

	//////////////////////////////////////////////////////////////////////
	//
	//  Setters
	//
	/////////////////////////////////////////////////////////////////

	// the sakaiSession object
	public Session sakaiSession;

	/*
	 * Setup the Sakai person manager
	 * 
	 */
	private SakaiPersonManager sakaiPersonManager;
	private UserDirectoryService userDirectoryService = ComponentManager.get(UserDirectoryService.class);
	private UsageSessionService usageSessionService = ComponentManager.get(UsageSessionService.class);
	private EmailService emailService = ComponentManager.get(EmailService.class);
	private SecurityService securityService = ComponentManager.get(SecurityService.class);

	public void setSakaiPersonManager(SakaiPersonManager spm) {
		sakaiPersonManager = spm;
	}

	private SakaiPersonManager getSakaiPersonManager() {
		if (sakaiPersonManager == null)
			sakaiPersonManager = (SakaiPersonManager)ComponentManager.get("org.sakaiproject.api.common.edu.person.SakaiPersonManager");

		return sakaiPersonManager;
	}

	private SqlService m_sqlService = null;
	public void setSqlService(SqlService sqs) {
		this.m_sqlService = sqs;
	}

	private SqlService getSqlService() {
		if (m_sqlService == null)
			m_sqlService = (SqlService)ComponentManager.get("org.sakaiproject.db.api.SqlService");

		return m_sqlService;
	}

	private CourseManagementAdministration courseAdmin;

	public CourseManagementAdministration getCourseAdmin() {
		if(courseAdmin == null){
			courseAdmin = (CourseManagementAdministration) ComponentManager.get(CourseManagementAdministration.class.getName());
		}
		return courseAdmin;
	}

	private CourseManagementService cmService;
	public void setCourseManagementService(CourseManagementService cms) {
		cmService = cms;
	}

	public CourseManagementService getCourseManagementService() {
		if(cmService == null){
			cmService = (CourseManagementService) ComponentManager.get(CourseManagementService.class.getName());
		}
		return cmService;
	}

	private EmailTemplateService getEmailTemplateService() {
		if (emailTemplateService == null) {
			emailTemplateService = (EmailTemplateService) ComponentManager.get(EmailTemplateService.class.getName());
		}
		return emailTemplateService;
	}

	private SessionManager getSessionManager() {
		if(sessionManager == null){
			sessionManager = (SessionManager) ComponentManager.get("org.sakaiproject.tool.api.SessionManager");
		}

		return sessionManager;
	}

	private NumberRoutingHelper numberRoutingHelper;
	private NumberRoutingHelper getNumberRoutingHelper() {
		if (numberRoutingHelper == null) {
			numberRoutingHelper = (NumberRoutingHelper) ComponentManager.get("org.sakaiproject.sms.logic.external.NumberRoutingHelper");
		}
		return numberRoutingHelper;
	}

	//////////////////////////////////////////////////////////////////////
	//
	// Constructors
	//
	//////////////////////////////////////////////////////////////////////

	public SpmlResponse doRequest(SpmlRequest req) {

		log.debug("SPMLRouter received request {}", req.getClass().getSimpleName());
		SpmlResponse resp = req.createResponse();

                SecurityAdvisor spmlAdvisor = new SecurityAdvisor() {
                        public SecurityAdvice isAllowed(String userId, String function, String reference) {

				// Update users
                                if (function.startsWith("user.") || function.startsWith("profile.") || "cm.admin".equals(function)) {
                                        return SecurityAdvice.ALLOWED;
                                }
                                else {
                                        return SecurityAdvice.PASS;
                                }
                        }
                };

		try {
			// we need to login
			log.debug("About to login");

			boolean sID = login(spmlUser);
			if (sID == false) {
                                log.error("Unable to login as '{}' to handle SPML request", spmlUser);
				resp.setError("Login failure");
				resp.setResult("failure");
				return resp;
			}

			// allow certain operations
			securityService.pushAdvisor(spmlAdvisor);

			if (req instanceof AddRequest) {
				AddRequest uctRequest = (AddRequest)req;
				try {
					resp = spmlAddRequest(uctRequest);
				}
				catch (Exception e) {
					log.warn("Exception handling AddRequest", e);
				}
			} else if (req instanceof ModifyRequest) {
				ModifyRequest uctRequest = (ModifyRequest)req;
				resp = spmlModifyRequest(uctRequest);
			} else if (req instanceof DeleteRequest) {
				DeleteRequest uctRequest = (DeleteRequest)req;
				resp = spmlDeleteRequest(uctRequest);
			}  else if (req instanceof BatchRequest) {
				BatchRequest uctRequest = (BatchRequest)req;
				resp = spmlBatchRequest(uctRequest);
			} else {
				log.error("SPML Method not implemented");
			}
		}
		catch (Exception e) {
			log.warn("Exception handling SPML request", e);
			resp.setError("Login failure");
			resp.setResult("failure");
			return resp;	
		} finally {
			logout();
			securityService.popAdvisor(spmlAdvisor);
		}
		return resp;
	}


	//////////////////////////////////////////////////////////////////////
	//
	// Responses
	//
	//////////////////////////////////////////////////////////////////////

	/**
	 * Convert error messages in a response into an SpmlException.
	 */
	public void throwErrors(SpmlResponse res) throws SpmlException {

		// now moved in here
		if (res != null)
			res.throwErrors();
	}

	/**
	 * Handle SPML AddRequest
	 *
	 */    
	public SpmlResponse spmlAddRequest(AddRequest req)  throws SpmlException {

		UserEdit thisUser = null;
		SakaiPerson userProfile = null;
		SakaiPerson systemProfile = null;

		/* Attributes are:
		   objectclass
		   CN
		   Surname
		   Full Name
		   Given Name
		   Initials
		   nspmDistributionPassword
		 */
		
		String CN = "";
		String GN = "";
		String oldType = null;

		CN =(String)req.getAttributeValue(FIELD_CN);
		log.info("SPML AddRequest: user {}", CN);

		SpmlResponse response = req.createResponse();

		// Return an error if the CN is null (undefined)
		if (CN == null) {
			log.error("ERROR: invalid username: {}", CN);
			response.setResult(SpmlResponse.RESULT_FAILURE);
			response.setError(SpmlResponse.ERROR_CUSTOM_ERROR);
			response.setErrorMessage("invalid username");
			logSPMLRequest("Addrequest",req.toXml(), null);
			return response;			
		}
		
		CN = CN.toLowerCase();
		
		// Log the request
		logSPMLRequest("Addrequest",req.toXml(),CN);

		if (req.getAttributeValue(FIELD_PN)!=null)
			GN = (String)req.getAttributeValue(FIELD_PN);
		else
			GN = (String)req.getAttributeValue(FIELD_GN);

		String LN = (String)req.getAttributeValue(FIELD_SURNAME);
		if (LN != null) {
			LN = LN.trim();
		}
		
		String type = null;
		String status = null;

		// We may not always get eduPersonPrimaryAffiliation, so infer it from the status
		if (req.getAttributeValue("uctStudentStatus") != null) {
			status = (String)req.getAttributeValue("uctStudentStatus");
			type = TYPE_STUDENT;
		}

		if (type == null && req.getAttributeValue("employeeStatus") != null) {
			status = (String)req.getAttributeValue("employeeStatus");
			type = TYPE_STAFF;
		}

		if (type == null && req.getAttributeValue("ucttpstatus") != null) {
			status = (String)req.getAttributeValue("ucttpstatus");
			type = TYPE_THIRDPARTY;
			
		}

		// Otherwise set type from eduPersonPrimaryAffiliation
		if (type == null) {
			type = (String)req.getAttributeValue(FIELD_TYPE);
		}

		// If we still can't determine the type, reject the request
		if (type == null || type.equals("")) {
			log.error("ERROR: no eduPersonPrimaryAffiliation: {}", CN);
			response.setResult(SpmlResponse.RESULT_FAILURE);
			response.setError(SpmlResponse.ERROR_CUSTOM_ERROR);
			response.setErrorMessage("no eduPersonPrimaryAffiliation");
			return response;
		}

		String originalType = type;
		
		// For staff, status could be null
		if (status == null && TYPE_STUDENT.equals(type))
		{
			status = STATUS_INACTIVE;
		} else if (status == null) {
			status = STATUS_ACTIVE;
		}

		// VULA-1268 status can be a bit funny
		if ("1Active".equals(status)) {
			log.debug("got status of 1active so assuming active");
			status = STATUS_ACTIVE;
		}

		log.info("user {} type is: {} and status is: {}", CN, type, status);

		// VULA-834 We create third-party accounts regardless of the "online learning required" field

		String mobile = (String)req.getAttributeValue(FIELD_MOBILE);
		if (mobile == null ) {
			mobile ="";
		} else {
			mobile = fixPhoneNumber(mobile);
		}

		String orgUnit = (String)req.getAttributeValue(FIELD_OU);
		String orgName = (String)req.getAttributeValue(FIELD_ORG_DECR);
		if (orgName == null )
			orgName = "";

		String orgCode = null;
		if (orgUnit == null ) {
			orgUnit="";
		} else {
			orgCode = getOrgCodeById(orgUnit, orgName);
		}

		boolean newUser = false;
		boolean sendNotification = false;

		try {
			User user = userDirectoryService.getUserByEid(CN);
			thisUser = userDirectoryService.editUser(user.getId());
			log.debug("allowAddUser for {} is {}", CN, userDirectoryService.allowAddUser());
			oldType = thisUser.getType();
		} 
		catch (UserNotDefinedException e)
		{
			// If the status is inactive, don't add the user
			if (STATUS_INACTIVE.equals(status)) {
				log.info("User {} doesn't exist on Vula but has status {} so not adding them", CN, status);
				response.setRequestId(SpmlResponse.RESULT_SUCCESS);
				return response;
			}

			// This user doesn't exist, so create it
			try {
				log.debug("About to try adding the user {}", CN);
				newUser = true;
				thisUser = userDirectoryService.addUser(null,CN);
				log.info("created account for user {}", CN);
			}
			catch (UserIdInvalidException in) {
				//should throw out here
				log.error("invalid username: {}", CN);
				response.setResult(SpmlResponse.RESULT_FAILURE);
				response.setError(SpmlResponse.ERROR_CUSTOM_ERROR);
				response.setErrorMessage("invalid username");
				return response;
			}
			catch (UserAlreadyDefinedException ex) {
				//should throw out here
				log.error("User already exists: {}", CN);
				response.setResult(SpmlResponse.RESULT_FAILURE);
				response.setError(SpmlResponse.ERROR_CUSTOM_ERROR);
				response.setErrorMessage("user already exists");
				return response;
			}
			catch (UserPermissionException ep) {
				//should throw out here
				log.error("no permision to add user", e);
				response.setResult(SpmlResponse.RESULT_FAILURE);
				response.setError(SpmlResponse.ERROR_CUSTOM_ERROR);
				response.setErrorMessage("No permission to add user");
				return response;
			}
		}
		catch (UserPermissionException e) {
			//should throw out here
			log.error("ERROR no permission ", e);
			response.setResult(SpmlResponse.RESULT_FAILURE);
			response.setError(SpmlResponse.ERROR_CUSTOM_ERROR);
			response.setErrorMessage("No permission to edit user");
			return response;
		}
		catch (UserLockedException ul) {
			//should throw out here
			log.error("ERROR user locked for editing: {}", CN);
			response.setResult(SpmlResponse.RESULT_FAILURE);
			response.setError(SpmlResponse.ERROR_CUSTOM_ERROR);
			response.setErrorMessage("User is locked for editing");
			return response;
		}

		//try get the profile
		log.debug("About to get the profiles");
		userProfile = getUserProfile(CN,"UserMutableType");
		log.debug("Got the user profile");
		systemProfile = getUserProfile(CN,"SystemMutableType");
		log.debug("Got the system profile");    

		if (StringUtils.isNotBlank(systemProfile.getSurname()) && StringUtils.isNotBlank(thisUser.getLastName())) {
			String systemSurname = systemProfile.getSurname();
			String modSurname = LN;		
			if (!systemSurname.equals(userProfile.getSurname())) {
				systemProfile.setSurname(modSurname);
			} else {
				userProfile.setSurname(modSurname);
				systemProfile.setSurname(modSurname);
				thisUser.setLastName(modSurname);
			}	
		} else {
			userProfile.setSurname(LN);
			systemProfile.setSurname(LN);
			thisUser.setLastName(LN); 	
		}

		if (StringUtils.isNotBlank(systemProfile.getGivenName()) && StringUtils.isNotBlank(thisUser.getFirstName())) {
			String systemGivenName = systemProfile.getGivenName();
			String modGivenName = GN;	
			if (!systemGivenName.equals(userProfile.getGivenName())) {
				systemProfile.setGivenName(modGivenName);
			} else {
				systemProfile.setGivenName(modGivenName);
				userProfile.setGivenName(modGivenName);
				thisUser.setFirstName(modGivenName);
			}
		} else {
			systemProfile.setGivenName(GN);
			userProfile.setGivenName(GN);
			thisUser.setFirstName(GN);
		}

		String thisEmail = (String)req.getAttributeValue(FIELD_MAIL);

		if (isValidEmail(thisEmail)) {

			log.debug("this email is: {}", thisEmail);

			if (systemProfile.getMail()!= null && !systemProfile.getMail().equals("") && thisEmail != null ) {
				String systemMail = systemProfile.getMail();
				String modMail= thisEmail;
				if (userProfile.getMail() != null && !systemMail.equalsIgnoreCase(userProfile.getMail()) && !userProfile.getMail().equals("")) {
					systemProfile.setMail(modMail);
				} else {
					systemProfile.setMail(modMail);
					userProfile.setMail(modMail);
					thisUser.setEmail(modMail);
				}

			} else if (thisEmail !=null && systemProfile.getMail() == null) {
				//if the account was created manually - profile state may be inconsistent
				systemProfile.setMail(thisEmail);
				sendNotification = true;
				//email may not have been set
				if (thisUser.getEmail() == null || "".equalsIgnoreCase(thisUser.getEmail())) {
					userProfile.setMail(thisEmail);
					thisUser.setEmail(thisEmail);
				} else {
					userProfile.setMail(thisUser.getEmail());
				}
			} else if (thisEmail != null && !thisEmail.equals("")) {
				//the SPML might now send null emails
				systemProfile.setMail(thisEmail);
				userProfile.setMail(thisEmail);
				thisUser.setEmail(thisEmail);
			}

			String systemMail = systemProfile.getMail();
			String userMail = userProfile.getMail();

			//Check for the uct.ac.za to myUCT migration bug
			if (systemMail != null && !systemMail.equalsIgnoreCase(userMail)) {
				if (forceUpdateMail(systemMail, userMail, type, CN)) {
					userProfile.setMail(thisEmail);
					thisUser.setEmail(thisEmail);
				}
			}

		} else {
			log.debug("Ignoring invalid or missing email: {}", thisEmail);
		}

		ResourceProperties rp = thisUser.getProperties();
		ZonedDateTime dt = ZonedDateTime.now();
		DateTimeFormatter fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
		String isoDate = dt.format(fmt);

		// Special case: if the user is inactive, set their email to eid@uct.ac.za
		if (STATUS_INACTIVE.equals(status)) {
			String inactiveMail = thisUser.getEid() + "@uct.ac.za";
			systemProfile.setMail(inactiveMail);
			userProfile.setMail(inactiveMail);
			thisUser.setEmail(inactiveMail);

			// Do we have an inactive flag?
			String deactivated = rp.getProperty(PROP_DEACTIVATED);
			if (deactivated == null) {
				rp.addProperty(PROP_DEACTIVATED, isoDate);
			}
		}

		log.debug("user's email profile email is {}", userProfile.getMail());

		if (STATUS_ACTIVE.equals(status) || STATUS_ADMITTED.equals(status)) {
			// remove the possible flag
			rp.removeProperty(PROP_DEACTIVATED);

			// do we have the clear data flag?
			String data = rp.getProperty(PROP_CONTENT_REMOVED);
			if (data != null) {
				// We want to keep the data but clear the flag
				rp.addProperty(PROP_DATA_CLEARED_LAST, data);
				rp.removeProperty(PROP_CONTENT_REMOVED);
			}
		} 

		// VULA-1297 add new update time
		rp.addProperty(PROP_SPML_LAST_UPDATE, isoDate);

		String thisTitle = (String)req.getAttributeValue(FIELD_TITLE);
		if (systemProfile.getTitle()!= null && !systemProfile.getTitle().equals("") && thisTitle != null) {
			String systemTitle = systemProfile.getTitle();
			String modTitle = thisTitle;
			if (userProfile.getTitle() != null && !systemTitle.equals(userProfile.getTitle()) && !userProfile.getTitle().equals("")) {
				systemProfile.setTitle(modTitle);
			}  else {
				systemProfile.setTitle(modTitle);
				userProfile.setTitle(modTitle);
			}
		} else if (thisTitle != null && !thisTitle.equals("")) {
			systemProfile.setTitle(thisTitle);
			userProfile.setTitle(thisTitle);
		}

		if (type != null ) {
			log.debug("got type: {} and status: {}", type, status);

			// VULA-1006 special case for inactive staff and third party: we set the email to eid@uct.ac.za
			if (TYPE_STUDENT.equals(type) && STATUS_INACTIVE.equals(status)) {
				type = STATUS_INACTIVE;
			} else if (TYPE_STAFF.equals(type) && STATUS_INACTIVE.equals(status)) {
				type = "inactiveStaff";
				String inactiveEmail = thisUser.getEid() + "@uct.ac.za";
				thisUser.setEmail(inactiveEmail);
                                systemProfile.setMail(inactiveEmail);
                                userProfile.setMail(inactiveEmail);
			} else if (TYPE_THIRDPARTY.equals(type) && STATUS_INACTIVE.equals(status)) {
				String inactiveEmail = thisUser.getEid() + "@uct.ac.za";
				type = "inactiveThirdparty";
				thisUser.setEmail(inactiveEmail);
                                systemProfile.setMail(inactiveEmail);
                                userProfile.setMail(inactiveEmail);
			} else	if (STATUS_ADMITTED.equals(status)) {
				type = TYPE_OFFER;
			}

			log.debug("got type: {} and status: {}", type, status);
			thisUser.setType(type);
			systemProfile.setPrimaryAffiliation(type);
			userProfile.setPrimaryAffiliation(type);
		}

		log.debug("Updating profile");

		// set the profile Common name
		systemProfile.setCommonName(CN);
		userProfile.setCommonName(CN);

		// this last one could be null
		String systemMobile = systemProfile.getMobile();
		String systemNormalizedMobile = systemProfile.getNormalizedMobile();
		//set up the strings for user update these will be overwritten for changed profiles

		String modMobile = mobile;

		//if the user surname != system surname only update the system 

		// VULA-716 Normalize the user's mobile phone number
		
		String userMobileNormalized = normalizeMobile(userProfile.getMobile());
		String newNumberNormalized = normalizeMobile(modMobile);

		if (systemMobile != null && systemNormalizedMobile == null) {
			systemNormalizedMobile = normalizeMobile(systemMobile);
		}

		if (systemMobile != null) {
			if (!systemNormalizedMobile.equals(userMobileNormalized)) {
				systemProfile.setMobile(modMobile);
				systemProfile.setNormalizedMobile(newNumberNormalized);
			} else {
				systemProfile.setMobile(modMobile);
				userProfile.setMobile(modMobile);
				systemProfile.setNormalizedMobile(newNumberNormalized);
				userProfile.setNormalizedMobile(newNumberNormalized);
			}
		} else if (systemMobile == null && modMobile != null) {
			systemProfile.setMobile(modMobile);
			userProfile.setMobile(modMobile);
			systemProfile.setNormalizedMobile(normalizeMobile(mobile));
			userProfile.setNormalizedMobile(normalizeMobile(mobile));
		}

		// Set the department number (systemOrgUnit) and 3 letter code (systemOrgCode, e.g. STA)

		String systemOrgCode = systemProfile.getOrganizationalUnit();
		String systemOrgUnit = systemProfile.getDepartmentNumber();
		String modOrgUnit = orgUnit;
		String modOrgCode = orgCode;

		// Department number
		if (systemOrgUnit != null) {
			if (!systemOrgUnit.equals(userProfile.getDepartmentNumber())) {
				systemProfile.setDepartmentNumber(modOrgUnit);
			} else {
				systemProfile.setDepartmentNumber(modOrgUnit);
				userProfile.setDepartmentNumber(modOrgUnit);
			}
		} else if (systemOrgUnit == null && modOrgUnit != null) {
			systemProfile.setDepartmentNumber(modOrgUnit);
			userProfile.setDepartmentNumber(modOrgUnit);				
		}

		// The 3 letter department code (e.g. STA)
		if (systemOrgCode != null) {
			if (systemOrgCode.equals(modOrgCode)) {
				systemProfile.setOrganizationalUnit(modOrgCode);
			} else {
				systemProfile.setOrganizationalUnit(modOrgCode);
				userProfile.setOrganizationalUnit(orgName);
			}
		} else if (systemOrgCode == null && modOrgCode != null) {
			systemProfile.setOrganizationalUnit(modOrgCode);
			userProfile.setOrganizationalUnit(orgName);				
		}

		// Set date of birth
		
		String DOB = (String)req.getAttributeValue(FIELD_DOB);
		if ( DOB != null) {
			//format is YYYYMMDD
			DateFormat fm = new SimpleDateFormat("yyyyMMdd");
			Date date;
			try {
				date = fm.parse(DOB);
				systemProfile.setDateOfBirth(date);
			} catch (ParseException e) {
				log.warn("Cannot parse date of birth: {}", DOB);
			}
		}

		// Save the user record and user and system profile records
		
		try {
			log.debug("Saving user details");
			userDirectoryService.commitEdit(thisUser);
			log.debug("Saving profile");
			sakaiPersonManager.save(systemProfile);			
			sakaiPersonManager.save(userProfile);
		} catch (UserAlreadyDefinedException e1) {
			log.warn("User {} already exists");
		} catch (Exception e) {
			log.warn("Exception saving user or profile", e);
                }

		// VULA-226 Send new user a notification
		if (sendNotification) {
			log.debug("Sending user notification");
			notifyNewUser(thisUser.getId(), type);
		}

		// For students, update association information: residences, programme code, faculty
		
		if (TYPE_STUDENT.equalsIgnoreCase(originalType)) {

			log.debug("Updating student course membership data");

			// Flag student for a course enrollment update from Peoplesoft
			recordStudentUpdate(thisUser);

			// Only do this if the user is active, otherwise the student is not yet or no longer registered
			if (STATUS_ACTIVE.equalsIgnoreCase(status) || STATUS_ADMITTED.equalsIgnoreCase(status)) {
				try {

					List<String> checkList = new ArrayList<String>();

					String courses = (String) req.getAttributeValue(FIELD_COURSES);
					String program = (String) req.getAttributeValue(FIELD_PROGRAM);
					String faculty = (String) req.getAttributeValue(FIELD_FACULTY);
					boolean hasCourses = StringUtils.isNotBlank(courses);

					log.debug("Student courses: {} program {} faculty {} hasCourses {}", courses, program, faculty, hasCourses);

					// Offer students get offer program codes set without a year (VULA-3938)

					if (StringUtils.isNotBlank(program) && !hasCourses) {
						// Single or list
						String[] programList = program.split(",");
						for (String programCode : programList) {
							addUserToCourse(CN, programCode, TERM_OFFER, null);
							checkList.add(programCode + "," + TERM_OFFER);
						}
					} else {
						// Programme code: only add if registered for at least one course
						if (StringUtils.isNotBlank(program) && hasCourses) {
							// Single or list
							String[] programList = program.split(",");
							for (String programCode : programList) {
								addUserToCourse(CN, programCode);
								checkList.add(programCode);
							}
						}
						
						// Faculty: only add if registered for at least one course
						if (StringUtils.isNotBlank(faculty) && hasCourses) {
							addUserToCourse(CN, faculty + "_STUD");
							checkList.add(faculty + "_STUD");
						}
					}

					// Residence information

					SimpleDateFormat yearf = new SimpleDateFormat("yyyy");
					String thisYear = yearf.format(new Date());

					// rescode is of the form RES*YYYY-MM-DD*YYYY-MM-DD, e.g. OBZ*2012-01-31*2012-12-15 
					// may contain old data (e.g. residence information from prior year)
					String resCode = (String) req.getAttributeValue(FIELD_RES_CODE);

					if (resCode != null ) {
						// if its a weird no-date residence ignore it
						if (resCode.indexOf("*") > 0 ) {
							resCode = resCode.substring(0,resCode.indexOf("*"));

							String year = (String)req.getAttributeValue(FIELD_RES_CODE);
							year = year.substring(year.indexOf("*") + 1,  year.indexOf("-"));
							log.info("residence found for: {} year: {}", resCode, year);
							
							// If its current add to the list for the sync job
							if (year.equals(thisYear) && residenceIsCurrent((String)req.getAttributeValue(FIELD_RES_CODE))) {
								checkList.add(resCode);
								this.addUserToCourse(CN, resCode, year, CAT_RESIDENCE);
							}
						}
					}
				
					// Remove from courses not contained in checkList
					synchCourses(checkList, CN);

				}
				catch (Exception e) {
					//error adding users to course
					log.warn("Exception adding student to course", e);
				}
			} else if (STATUS_INACTIVE.equalsIgnoreCase(status)) {
				// Clear current year faculty, program code, residence if inactive or admitted but not yet registered 
				synchCourses(new ArrayList<String>(), CN);
			}
		}

		log.debug("Finished AddRequest");

		return response;
	} 


	/**
	 * Should we force the update of this user's email?
	 * done when there is an myuct equivalent legacy mail account 
	 * @param systemMail
	 * @param userMail
	 * @param type
	 * @param the user's eid
	 * @return
	 */
	private boolean forceUpdateMail(String systemMail, String userMail,
			String type, String eid) {
		if (!type.equals(TYPE_STUDENT))
			return false;

		if (userMail.equalsIgnoreCase(eid + "@uct.ac.za"))
			return true;

		return false;
	}


	/**
	 * Normalize mobile phone number
	 * @param mobile
	 * @return normalized number
	 */
	private String normalizeMobile(String mobile) {
		numberRoutingHelper = getNumberRoutingHelper();
		if (numberRoutingHelper == null) {
			log.error("no numberRoutingHelper available for normalizing mobile numbers");
			return mobile;
		}

		return numberRoutingHelper.normalizeNumber(mobile);
	}


	private EmailTemplateService emailTemplateService;

	/**
	 * Send a notification email to a newly added user
	 * @param userId The user's internal Sakai ID
	 * @param type
	 */
	private void notifyNewUser(String userId, String type) {
		String prefix = "spml.";

		UserEdit ue = null;
		try {
			ue = userDirectoryService.editUser(userId);
		}
		catch (Exception uex) {
			log.warn("failed to get user: {}", userId);
			return;
		} 
		
		log.info("got user: {} with email {}", ue.getDisplayId(), ue.getEmail());

		if (ue.getEmail() == null) {
			userDirectoryService.cancelEdit(ue);
			return;
		}

		ResourceProperties rp = ue.getProperties();

		// Offer students have email accounts, so treat them as students

		if (TYPE_OFFER.equals(type)) {
			type = TYPE_STUDENT;
		}

		if (rp.getProperty(PROP_SENT_EMAIL) == null) {

			// offer students have email accounts

			Map<String, String> replacementValues = new HashMap<String, String>();
			replacementValues.put("userEid", ue.getDisplayId());
			replacementValues.put("userFirstName", ue.getFirstName());
			replacementValues.put("userLastName", ue.getLastName());
			replacementValues.put("userEmail", ue.getEmail());

			emailTemplateService = getEmailTemplateService();

			EmailTemplate template = emailTemplateService.getEmailTemplate(prefix + type, null);
			if (template != null) {
				log.info("send mail to:{} subject: {}", ue.getEmail(), template.getSubject());
				List<String> recipient = new ArrayList<String>();
				recipient.add(ue.getReference());
				log.debug("setting recipient list of size {}", recipient.size());
				emailTemplateService.sendRenderedMessages(prefix + type, recipient, replacementValues, "help@vula.uct.ac.za", "Vula Help");
			} else {
				userDirectoryService.cancelEdit(ue);
				return;
			}

			try {
				ResourcePropertiesEdit rpe = ue.getPropertiesEdit();
				rpe.addProperty(PROP_SENT_EMAIL, "true");
				userDirectoryService.commitEdit(ue);
			}
			catch (Exception e) {
				log.warn("Exception saving user", e);
			}
		}
	}


	/**
	 * SPML DeleteRequest - not handled, so log and ignore 
	 * @param req
	 * @return
	 */
	public SpmlResponse spmlDeleteRequest(SpmlRequest req) {

		log.warn("SPML DeleteRequest (not handled)");
		this.logSPMLRequest("DeleteRequest", req.toXml(), null);
		
		SpmlResponse response = null;
		return response;
	} 

	/**
	 * SPML ModifyRequest - not handled, so log and ignore
	 */
	@SuppressWarnings("unchecked")
	public SpmlResponse spmlModifyRequest(ModifyRequest req) {

		log.warn("SPML ModifyRequest (not handled)");
		this.logSPMLRequest("ModifyRequest", req.toXml(), null);

		SpmlResponse response = null;
		return response;
	}

	/**
	 * SPML Batch Request - call the individual methods
	 * @param req
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public SpmlResponse spmlBatchRequest(BatchRequest req) {

		log.info("SPML BatchRequest");
		SpmlResponse resp = null;

		try {
			// we need to iterate through through the units in the batch
			
			// get a list of the actual methods
			List<SpmlRequest> requestList = req.getRequests(); 
			for (int i =0 ; i < requestList.size(); i++) {
				//each item in the list these should be an spml object...
				//these can be any of the types
				SpmlRequest currReq = (SpmlRequest)requestList.get(i);

				if (currReq instanceof AddRequest) {
					AddRequest uctRequest = (AddRequest)currReq;
					resp = spmlAddRequest(uctRequest);
				} else if (currReq instanceof ModifyRequest) {
					ModifyRequest uctRequest = (ModifyRequest)currReq;
					resp = spmlModifyRequest(uctRequest);
				} else if (currReq instanceof DeleteRequest) {
					DeleteRequest uctRequest = (DeleteRequest)currReq;
					resp = spmlDeleteRequest(uctRequest);
				}	

				//create the response	
				resp = req.createResponse();
			} //end the for loop

		}
		catch (Exception e) {
			log.warn("Exception handling batch request", e);
		}

		return resp;
	} //end method


	private SessionManager sessionManager;
	public void setSessionManager(SessionManager sm) {
		sessionManager = sm;
	}

	// we'll need to handle login ourselves
	private boolean login(String eid) {

		String serverName = ServerConfigurationService.getServerName();
		log.debug("SPML logging in on {} as {}", serverName, eid);

                UsageSession session = usageSessionService.startSession(eid, serverName, "SPML");
		if (session == null) {
			log.error("login failed for {}", eid);
			return false;
		}

                sakaiSession = getSessionManager().getCurrentSession();
		if (sakaiSession == null) {
			return false;
		}

		if (setSakaiSessionUser(eid)) {
			log.debug("Logged in as {}", eid);
			return true;
		} else {
			log.debug("Unable to login as {}", eid);
			return false;
		}
	}

	private void logout() {
		usageSessionService.logout();
	}

	/*
	 * Methods for accessing and editing user profiles
	 * contributed by Nuno Fernandez (nuno@ufp.pt)
	 */	

	/**
	 * Get a user profile record (user or system)
	 */
	private SakaiPerson getUserProfile(String userEid, String type) {

		// UserEids must be lower case
		userEid = userEid.toLowerCase();
		this.getSakaiPersonManager();

		Type _type = null;
		if (type.equals("UserMutableType")) {
			setSakaiSessionUser(userEid); // switch to that user's session
			_type = sakaiPersonManager.getUserMutableType();
		} else {
			_type = sakaiPersonManager.getSystemMutableType();
		}

		SakaiPerson sakaiPerson = null;
		
		try
		{	
			User user = userDirectoryService.getUserByEid(userEid);
			sakaiPerson = sakaiPersonManager.getSakaiPerson(user.getId(), _type);
			
			// create profile if it doesn't exist
			if(sakaiPerson == null){
				sakaiPerson = sakaiPersonManager.create(user.getId(),_type);
				log.info("creating profile for user {} of type {}", userEid, _type.getDisplayName());
				
				//we need to set the privacy
				sakaiPerson.setHidePrivateInfo(Boolean.valueOf(true));
				sakaiPerson.setHidePublicInfo(Boolean.valueOf(false));

				// set the email to null rather than an empty string if we don't have an email
				// create() method will by default set this to user.getEmail() which returns empty string for null

				if ("".equals(user.getEmail())) {
					sakaiPerson.setMail(null);
				}
			}
		}	
		catch(Exception e){
			log.error("Unknown error occurred in getUserProfile({}): ", userEid, e.getMessage());
		}

		if (type.equals("UserMutableType")) {
			// return to the SPML user
			setSakaiSessionUser(spmlUser);
		}

		return sakaiPerson;
	}

	/**
	 * Set the session to the new user
	 */
	private synchronized boolean setSakaiSessionUser(String eid) {
		try {
			User user = userDirectoryService.getUserByEid(eid);
			sakaiSession.setUserId(user.getId());
			sakaiSession.setUserEid(eid);
		}
		catch (Exception e)
		{
			log.error("Error switching user to eid {}: {}", eid, e.getMessage());
			return false;
		}

		return true;
	} 

	/**
	 * Add the user to the SPML_UPDATED_USERS table as a trigger to the external Peoplesoft integration script
	 * @param u
	 */
	private void recordStudentUpdate(User u) {
		getSqlService();

		String sql = "select userEid from " + TBL_UPDATED_USERS + " where userEid = ?";
		Object[] fields = new Object[]{u.getEid()};
		List<String> result = m_sqlService.dbRead(sql, fields, null);
		
		if (result == null || result.size() == 0) {
			sql = "insert into " + TBL_UPDATED_USERS + " (userEid, dateQueued) values (?, NOW())";
			fields = new Object[] { u.getEid() };
			m_sqlService.dbWrite(sql, fields);
		}
	}

	/**
	 * Log the SPML request to the spml_log table
	 */
	private void logSPMLRequest(String type, String body, String CN) {
		try {
			if (CN == null) {
				CN = "null";
			}
			String statement = "insert into " + TBL_SPML_LOG + " (spml_type, spml_body, userEid) values (? ,? ,?)";

			Object[] fields = new Object[]{
					type,
					body,
					CN
			};
			
			getSqlService();
			m_sqlService.dbWrite(statement, fields);

		}
		catch (Exception e) {
			log.warn("Exception logging SPML request", e);
		}

	}


	/**
	 * Add the user to a course, with default values for term and setCategory
	 */
	private void addUserToCourse(String userEid, String courseCode) {
		addUserToCourse(userEid, courseCode, null, null);
	}

	/**
	 * Add the user to a course
	 * @param userEid
	 * @param courseCode
	 * @param term
	 * @param setCategory - CM category. In use: Department, course, degree, faculty, Residence, NULL 
	 */
	private void addUserToCourse(String userEid, String courseCode, String term, String setCategory) {
		log.debug("addUserToCourse({}, {}, {}, {})", userEid, courseCode, term, setCategory);

		try {
			courseCode = courseCode.toUpperCase().trim();
			
			// Get the Course Management services
			courseAdmin = getCourseAdmin();
			cmService = getCourseManagementService();
			
			if (courseCode == null || courseCode.length() == 0) {
				return;
			}

			// Get the role based on the type of object this is
			String role = "Student";
			String setId = null;
			if (setCategory == null ) {
				if (courseCode.length() == 5) {
					setId = courseCode.substring(0,2);
					setCategory = CAT_DEGREE;
				} else {
					setId = courseCode.substring(0,3);
					setCategory = CAT_DEPT;
				}
			} else {
				setId = courseCode;
			}

			if (setCategory.equalsIgnoreCase(CAT_RESIDENCE)) {
				role = "Participant";
			}

			String courseEid = null;
			if (term == null) {
				SimpleDateFormat yearf = new SimpleDateFormat("yyyy");
				String thisYear = yearf.format(new Date());
				courseEid = getPreferredSectionEid(courseCode, thisYear);
				term = courseEid.substring(courseEid.indexOf(",") + 1);
				log.debug("term is {}", term);
			} else {
				//we already have a specific term
				courseEid = courseCode + "," +term;
			}

			// do we have an academic session?
			if (!cmService.isAcademicSessionDefined(term)) {

				if (TERM_OFFER.equals(term)) {
					Calendar cal = Calendar.getInstance();
					cal.set(2021, 1, 1);
					Date start =  cal.getTime();
					cal.set(2099, Calendar.DECEMBER, 31);
					Date end = cal.getTime();
					courseAdmin.createAcademicSession(term, term, term, start, end);
				} else {
					Calendar cal = Calendar.getInstance();
					cal.set(new Integer(term).intValue(), 1, 1);
					Date start =  cal.getTime();
					cal.set(new Integer(term).intValue(), Calendar.DECEMBER, 30);
					Date end = cal.getTime();
					courseAdmin.createAcademicSession(term, term, term, start, end);
				}
			}
			
			// does the course set exist?
			if (!cmService.isCourseSetDefined(setId)) 
				courseAdmin.createCourseSet(setId, setId, setId, setCategory, null);

			// is there a canonical course?
			if (!cmService.isCanonicalCourseDefined(courseCode)) {
				courseAdmin.createCanonicalCourse(courseCode, courseCode, courseCode);
				courseAdmin.addCanonicalCourseToCourseSet(setId, courseCode);
			}

			if (!cmService.isCourseOfferingDefined(courseEid)) {
				
				// Create a new course offering for this course if it doesn't exist yet

				log.info("creating course offering for {} in year {}", courseCode, term);
				emailService.send("help-team@vula.uct.ac.za", "help-team@vula.uct.ac.za", 
						"[CM]: new course created on vula: " + courseEid, 
						"[CM]: new course created on vula: " + courseEid, null, null, null);
				
				// If this is being created by SPML, it is current now (except for offer terms)
				Date startDate = new Date();

				Calendar cal2 = Calendar.getInstance();

				if (TERM_OFFER.equals(term)) {
					// Offer program codes always predate the current year
					cal2.set(Calendar.DAY_OF_MONTH, 1);
					cal2.set(Calendar.MONTH, Calendar.JANUARY);
					cal2.set(Calendar.YEAR, 2001);
					startDate = cal2.getTime();
				} else {
					// Use the term date
					cal2.set(Calendar.DAY_OF_MONTH, 31);
					cal2.set(Calendar.MONTH, Calendar.DECEMBER);
					if (term != null) {
						cal2.set(Calendar.YEAR, Integer.valueOf(term));
					}
				}

				// If this is a residence, the end date is later.
				if (setCategory.equalsIgnoreCase(CAT_RESIDENCE)) {
					cal2.set(Calendar.DAY_OF_MONTH, 31);
					cal2.set(Calendar.MONTH, Calendar.DECEMBER);
				}

				Date endDate = cal2.getTime();
				log.debug("got cal: {}/{}/{}", cal2.get(Calendar.YEAR), cal2.get(Calendar.MONTH), cal2.get(Calendar.DAY_OF_MONTH));
				
				courseAdmin.createCourseOffering(courseEid, courseEid, "someDescription", "active", term, courseCode, startDate, endDate);
				courseAdmin.addCourseOfferingToCourseSet(setId, courseEid);
			}

			// we know that all objects to this level must exist
			EnrollmentSet enrollmentSet = null;
			if (!cmService.isEnrollmentSetDefined(courseEid)) {
				enrollmentSet =  courseAdmin.createEnrollmentSet(courseEid, "title", "description", "category", "defaultEnrollmentCredits", courseEid, null);
			} else {
				enrollmentSet = cmService.getEnrollmentSet(courseEid);
			}

			if (!cmService.isSectionDefined(courseEid)) {
				courseAdmin.createSection(courseEid, courseEid, "description", CAT_COURSE, null, courseEid, enrollmentSet.getEid());
			} else {
				Section section = cmService.getSection(courseEid);
				// Check the section has a properly defined Enrollment set
				if (section.getEnrollmentSet() == null) {
					EnrollmentSet enrolmentSet = cmService.getEnrollmentSet(courseEid);
					section.setEnrollmentSet(enrolmentSet);
					section.setCategory(CAT_COURSE);
					courseAdmin.updateSection(section);
				}
			}

			log.info("adding student {} to {}", userEid, courseEid);
			courseAdmin.addOrUpdateSectionMembership(userEid, role, courseEid, "enrolled");
			courseAdmin.addOrUpdateEnrollment(userEid, courseEid, "enrolled", "NA", "0");

			// now add the user to a section of the same name
			// TODO this looks like duplicate logic

			try {
				cmService.getSection(courseEid);
			} 
			catch (IdNotFoundException id) {
				//create the CO
				//create enrollmentset
				courseAdmin.createEnrollmentSet(courseEid, courseEid, "description", "category", "defaultEnrollmentCredits", courseEid, null);
				log.info("creating Section for {} in year {}", courseCode, term);
				getCanonicalCourse(courseCode);
				courseAdmin.createSection(courseEid, courseEid, "someDescription", CAT_COURSE, null, courseEid, courseEid);
			}
			courseAdmin.addOrUpdateSectionMembership(userEid, role, courseEid, "enrolled");
		}
		catch(Exception e) {
			log.warn("Exception adding user to course:", e);
		}
	}


	private String getPreferredSectionEid(String courseCode, String term) {
		/* TODO we need the get the active CM for this course
		 * This will involve refactoring code below
		 */
		String courseEid = null;
		log.debug("about to get sections in {}", courseCode);
		List<CourseOffering> sections  = cmService.findActiveCourseOfferingsInCanonicalCourse(courseCode);
		log.debug("got {} sections", sections.size());

		if (sections.size() > 0) {
			//if there are multiple courses we will add them to the one in the later academic year
			CourseOffering co = getPreferredSection(sections);
			courseEid = co.getEid();
			log.debug("Found active course: {}", courseEid);
		} else {
			//use the not found info from below
			//does the 
			courseEid = courseCode + "," +term;
		}
		
		return courseEid;
	}

	
	private CourseOffering getPreferredSection(List<CourseOffering> sections) {

		if (sections.size() == 1) {
			return sections.get(0);
		}

		CourseOffering preferredOffering = null;
		// we want the one in the later year
		for (CourseOffering co : sections) {
			if (preferredOffering == null) {
				preferredOffering = co;
			} else {
				AcademicSession preferredSection = preferredOffering.getAcademicSession();
				AcademicSession session = co.getAcademicSession();
				if (session.getStartDate().after(preferredSection.getStartDate())) {
					preferredOffering = co;
				}
			}
		}

		log.info("found preferred offering of {}", preferredOffering.getEid());
		return preferredOffering;
	}


	/**
	 * Remove user from old courses
	 * @param uctCourse List of courses for the user provided by the SPML update, excluding the year
	 * @param userEid
	 */
	private void synchCourses(List<String> uctCourse, String userEid) {

		log.debug("Checking enrollments for {}", userEid);

		courseAdmin = getCourseAdmin();
		cmService = getCourseManagementService();

		// Qualify the list of courses passed in from the SPML update with a year and convert to upper-case
		
		List<String> finalCourses = new ArrayList<String>();
		for (String thisCourse : uctCourse) {
			
			if (log.isDebugEnabled()) {
				log.debug("courseList contains: {}", thisCourse);
			}
			
			// we need a fully qualified id for the section
			if (thisCourse.endsWith(TERM_OFFER))  {
				finalCourses.add(thisCourse);
			} else {
				// we need a fully qualified id for the section
				SimpleDateFormat yearf = new SimpleDateFormat("yyyy");
				String thisYear = yearf.format(new Date());
				String newSection = getPreferredSectionEid(thisCourse, thisYear);
				finalCourses.add(newSection.toUpperCase());
			}
		}

		// VULA-1256 we need all enrolled sets that are current or future
		Set<EnrollmentSet> enrolled = getCurrentFutureEnrollments(userEid);

		// Filter out all course groups (exclude everything except program codes and faculty groups)
		enrolled = filterCourseList(enrolled);

		log.debug("got list of enrollment set with {}, checklist contains {}", enrolled.size(), uctCourse.size());

		// Remove the student from any 'courses' (faculty, program, residence codes) which they're enrolled in that aren't contained in finalCourses
		// CM courses are always qualified with a year and always in upper case.
			
		for (EnrollmentSet eSet : enrolled) {
			
			String courseEid =  eSet.getEid();
			
			if (finalCourses.contains(courseEid)) {
				log.debug("retaining student {} membership in {}", userEid, courseEid);
			} else {
				log.info("removing student {} from {}", userEid, courseEid);
				courseAdmin.removeCourseOfferingMembership(userEid, courseEid);
				courseAdmin.removeSectionMembership(userEid, courseEid);
				courseAdmin.removeEnrollment(userEid, courseEid);
			}
		
		} // for
	}

	private Set<EnrollmentSet> filterCourseList(Set<EnrollmentSet> enrolled) {
		Set<EnrollmentSet> ret = new HashSet<EnrollmentSet>();

		Iterator<EnrollmentSet> it = enrolled.iterator();
		while (it.hasNext()) {
			EnrollmentSet set = it.next();
			String sectionEid = set.getEid();
			if (doSection(sectionEid)) {
				ret.add(set);
			}
		}

		return ret;
	}

	/**
	 * We are only interested in non-course sections no other types. 
	 * @param section
	 * @return
	 */
	private boolean doSection(String section) {
		
		// TODO replace checks with regexps
		
		// Faculty group: FFF_STUD,YYYY e.g. SCI_STUD,2014
		
		if (section.indexOf("_STUD") > 0) {
			log.debug("{} looks like a faculty code", section);
			return true;
		}

		// Program code: PPNNN,YYYY e.g. SB014,2014
		
		if (section.length() == "SB014,2014".length()) {
			log.debug("{} looks like a program code", section);
			return true;
		} 
		
		if (section.endsWith(TERM_OFFER)) {
			log.debug("{} looks like an offer program code", section);
			return true;
		}

		// Residence code: RRR,YYYY e.g. OBZ,2014

		if (section.length() == "OBZ,2014".length()) {
			log.debug("{} looks like a residence code", section);
			return true;
		} 
						
		log.debug("we don't work with {}", section);
		return false;
	}

	private Set<EnrollmentSet> getCurrentFutureEnrollments(String userEid) {
		log.debug("getCurrentFutureEnrollments({})", userEid);
		Set<EnrollmentSet> ret = new HashSet<EnrollmentSet>();

		Set<Section> sections = cmService.findEnrolledSections(userEid);
		Iterator<Section> it = sections.iterator();
		
		while (it.hasNext()) {
			Section section = it.next();
			CourseOffering courseOffering = cmService.getCourseOffering(section.getCourseOfferingEid());
			// we may have old ones without dates
			if (courseOffering.getStartDate() == null || courseOffering.getEndDate() == null) {
				log.debug("Course offering {} is missing start or end date", courseOffering.getEid());
				continue;
			}

			// is it an offer term or current
			if (courseOffering.getEid().endsWith(TERM_OFFER) ||
			    (new Date().after(courseOffering.getStartDate()) && new Date().before(courseOffering.getEndDate()))) {
				log.debug("offering {} is offer or current", courseOffering.getEid());
				ret.add(cmService.getEnrollmentSet(section.getEid()));
			} else if (new Date().before(courseOffering.getStartDate()) ) {
				log.debug("offering {} is in the future", courseOffering.getEid());
				ret.add(cmService.getEnrollmentSet(section.getEid()));
			} else {
				log.debug("not checking {} start: {} end: {}", courseOffering.getEid(), courseOffering.getStartDate(), courseOffering.getEndDate());
			}

		}

		return ret;
	}


	/** 
	 * Get the canonical course from the CM service, and if it doesn't exist, create it
	 * @param courseCode
	 */
	private void getCanonicalCourse(String courseCode) {
		try {
			cmService.getCanonicalCourse(courseCode);
		}
		catch (IdNotFoundException id) {
			log.info("creating canonicalcourse {}", courseCode);
			courseAdmin.createCanonicalCourse(courseCode, "something", "something else");
		}
	}


	/**
	 * Remove punctuation from phone number
	 * @param number
	 * @return
	 */
	private String fixPhoneNumber(String number) {
		number = number.replaceAll("/","");
		number = number.replaceAll("-","");
		number = number.replaceAll(" ","");

		// VULA-2131 Fix this here rather than in sms/impl/src/java/org/sakaiproject/sms/logic/external/NumberRoutingHelperImpl.java
		// because it's Peoplesoft- and UCT-specific. Change numbers like 02708x1234567 > 08x1234567
		if (number.startsWith("0270") && number.length() == 13 ) {
			number = number.substring(3);
		}

		return number;
	}

	/**
	 * Lookup three-letter department code from org unit number
	 * @param modOrgUnit
	 * @param modOrgName
	 * @return
	 */
	@SuppressWarnings("unchecked")
	private String getOrgCodeById(String modOrgUnit, String modOrgName) {
		String statement = "Select org from " + TBL_DEPT_LOOKUP + " where ORG_UNIT = ?";
		Object[] fields = new Object[]{Integer.valueOf(modOrgUnit)};
		List<String> result = m_sqlService.dbRead(statement, fields, null);
		if (result.size()>0) {
			log.debug("got org unit of {}", (String) result.get(0));
			return (String) result.get(0);
		} else {
			log.info("Unknown org code of {} received: adding", modOrgUnit);
			insertOrg(modOrgUnit, modOrgName);
		}

		return null;
	}

	/**
	 * Add three-letter department code and number to the lookup table
	 * @param modOrgUnit
	 * @param modOrgName
	 */
	@SuppressWarnings("unchecked")
	private void insertOrg(String modOrgUnit, String modOrgName) {
		// Does it exist or is it just null
		String statement = "Select org_unit from " + TBL_DEPT_LOOKUP + " where ORG_UNIT = ?";
		Object[] fields = new Object[]{modOrgUnit};
		List<String> result = m_sqlService.dbRead(statement, fields, null);
		if (result.size() == 0) {
			statement = "insert into " + TBL_DEPT_LOOKUP + " (description,org_unit) values (?, ?)";
			fields = new Object[]{modOrgName, Integer.valueOf(modOrgUnit)};
			m_sqlService.dbWrite(statement, fields);
		}
	}

	/**
	 * Check if a residence code is current
	 * @param resCode
	 * @return
	 */
	private boolean residenceIsCurrent(String resCode) {

		if (resCode == null)
			return false;

		log.debug("checking if this resCode is current: {}", resCode);

		/* rescode is of the form RES*YYYY-MM-DD*YYYY-MM-DD
		 * we need to parse these to dates
		 */
		try {
			String endDate = resCode.substring(15);
			DateFormat df = new SimpleDateFormat("yyyy-MM-dd");
			Date end = df.parse(endDate);

			if (end.before(new Date())) {
				log.debug("residence end date is in the past");
				return false;
			}

		} catch (ParseException e) {
			log.warn("Error parsing residence dates {}", resCode);
		} catch (Exception ex) {
			log.warn("Exception handling residence code", ex);
		}

		return true;
	}


        /**
         * Is this a valid email?
         * @param email
         * @return
         */
        private boolean isValidEmail(String email) {

                if (email == null || email.equals(""))
                        return false;

                email = email.trim();
                //must contain @
                if (email.indexOf("@") == -1)
                        return false;

                //an email can't contain spaces
                if (email.indexOf(" ") > 0)
                        return false;

                //use commons-validator
                EmailValidator validator = EmailValidator.getInstance();
                if (validator.isValid(email))
                        return true;

                return false;
        }


} //end class
