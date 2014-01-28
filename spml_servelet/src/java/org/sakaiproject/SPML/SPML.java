/**********************************************************************************
 * $URL: $
 * $Id: $
 ***********************************************************************************
 *
 * Copyright (c) 2003, 2004, 2005 Sakai Foundation
 * 
 * Licensed under the Educational Community License Version 1.0 (the "License");
 * By obtaining, using and/or copying this Original Work, you agree that you have read,
 * understand, and will comply with the terms and conditions of the Educational Community License.
 * You may obtain a copy of the License at:
 * 
 *      http://cvs.sakaiproject.org/licenses/license_1_0.html
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE
 * AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING 
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 **********************************************************************************/

/**
 * <p>
 * An implementation of an SPML client that creates user accounts and populates profiles
 * </p>
 * 
 * @author David Horwitz, University of Cape Town
 * 
 * @version $Revision: 3197 $
 */
package org.sakaiproject.SPML;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.openspml.client.SOAPClient;
import org.openspml.client.SOAPMonitor;
import org.openspml.message.AddRequest;
import org.openspml.message.BatchRequest;
import org.openspml.message.DeleteRequest;
import org.openspml.message.Modification;
import org.openspml.message.ModifyRequest;
import org.openspml.message.SpmlRequest;
import org.openspml.message.SpmlResponse;
import org.openspml.server.SpmlHandler;
import org.openspml.util.SpmlBuffer;
import org.openspml.util.SpmlException;
import org.sakaiproject.api.common.edu.person.SakaiPerson;
import org.sakaiproject.api.common.edu.person.SakaiPersonManager;
import org.sakaiproject.api.common.type.Type;
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
import org.sakaiproject.email.cover.EmailService;
import org.sakaiproject.emailtemplateservice.model.EmailTemplate;
import org.sakaiproject.emailtemplateservice.service.EmailTemplateService;
import org.sakaiproject.entity.api.ResourceProperties;
import org.sakaiproject.entity.api.ResourcePropertiesEdit;
import org.sakaiproject.sms.logic.external.NumberRoutingHelper;
import org.sakaiproject.tool.api.Session;
import org.sakaiproject.tool.api.SessionManager;
import org.sakaiproject.user.api.User;
import org.sakaiproject.user.api.UserAlreadyDefinedException;
import org.sakaiproject.user.api.UserEdit;
import org.sakaiproject.user.api.UserIdInvalidException;
import org.sakaiproject.user.api.UserLockedException;
import org.sakaiproject.user.api.UserNotDefinedException;
import org.sakaiproject.user.api.UserPermissionException;
import org.sakaiproject.user.cover.UserDirectoryService;

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
	private static final String FIELD_SCHOOL ="uctFaculty";
	private static final String FIELD_MOBILE = "mobile";
	private static final String FIELD_PROGAM = "uctProgramCode";
	private static final String FIELD_OU = "OU";
	private static final String FIELD_DOB = "DOB";
	private static final String FIELD_RES_CODE ="uctResidenceCode";
	private static final String FIELD_ORG_DECR = "uctorgaffiliation";
	private static final String FIELD_TITLE = "uctPersonalTitle";
	private static final String FIELD_ACADEMIC_CARREER = "uctAcademicCareer";

	// User account types
	private static final String TYPE_OFFER = "offer";
	private static final String TYPE_STUDENT = "student";
	private static final String TYPE_STAFF = "staff";
	private static final String TYPE_THIRDPARTY = "thirdparty";

	// SPML user status
	private static final String STATUS_ACTIVE = "Active";
	private static final String STATUS_INACTIVE = "Inactive";
	private static final String STATUS_ADMITTED = "Admitted";

	// Auth details 
	private static final String SPML_USER = ServerConfigurationService.getString("spml.user", "admin");
	private static final String SPML_PASSWORD = ServerConfigurationService.getString("spml.password", "admin");

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

	private static final Log LOG = LogFactory.getLog(SPML.class);

	//////////////////////////////////////////////////////////////////////
	//
	//  Setters
	//
	/////////////////////////////////////////////////////////////////
	public void init()
	{
		LOG.info(this + " init()");	
	}

	public void destroy() 
	{
		LOG.info(this + " destroy()");
	}

	// the sakaiSession object
	public Session sakaiSession;

	/*
	 * Setup the Sakai person manager
	 * contributed by Nuno Fernandez (nuno@ufp.pt)
	 * 
	 */
	private SakaiPersonManager sakaiPersonManager;

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

		LOG.debug("SPMLRouter received req " + req + " (id) ");
		SpmlResponse resp = req.createResponse();

		try {
			// we need to login
			LOG.debug("About to login");

			boolean sID = login(SPML_USER, SPML_PASSWORD);
			if (sID == false) {
				resp.setError("Login failure");
				resp.setResult("failure");
				return resp;
			}

			if (req instanceof AddRequest) {
				AddRequest uctRequest = (AddRequest)req;
				try {
					resp = spmlAddRequest(uctRequest);
				}
				catch (Exception e) {
					e.printStackTrace();
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
				LOG.error("SPML Method not implemented");
			}
		}
		catch (Exception e) {
			e.printStackTrace();
			resp.setError("Login failure");
			resp.setResult("failure");
			return resp;	
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
		LOG.info("SPML AddRequest: user " + CN);

		SpmlResponse response = req.createResponse();

		// Return an error if the CN is null (undefined)
		if (CN == null) {
			LOG.error("ERROR: invalid username: " + CN);
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
		
		String thisEmail = (String)req.getAttributeValue(FIELD_MAIL);
		String thisTitle = (String)req.getAttributeValue(FIELD_TITLE);

		String type = (String)req.getAttributeValue(FIELD_TYPE);
		String originalType = type;

		// If eduPerson is null, reject
		if (type == null || type.equals("")) {
			LOG.error("ERROR: no eduPersonPrimaryAffiliation: " + CN);
			response.setResult(SpmlResponse.RESULT_FAILURE);
			response.setError(SpmlResponse.ERROR_CUSTOM_ERROR);
			response.setErrorMessage("no eduPersonPrimaryAffiliation");
			return response;
		}

		type = type.toLowerCase();
		String status = STATUS_ACTIVE;
		if (TYPE_STUDENT.equals(type)) {
			status = (String)req.getAttributeValue("uctStudentStatus");
		} else if  (TYPE_STAFF.equals(type)) {
			status = (String)req.getAttributeValue("employeeStatus");
		} else if (TYPE_THIRDPARTY.equals(type)) {
			status = (String)req.getAttributeValue("ucttpstatus");
		}

		LOG.info("user status is: " + status);
		
		// For staff, status could be null
		if (status == null && TYPE_STUDENT.equals(type))
		{
			status = STATUS_INACTIVE;
		} else if (status == null) {
			status = STATUS_ACTIVE;
		}

		// VULA-1268 status can be a bit funny
		if ("1Active".equals(status)) {
			LOG.debug("got status of 1active so assuming active");
			status = STATUS_ACTIVE;
		}

		// VULA-834 We create third-party accounts regardless of the "online learning required" field
		/*
		String onlineRequired = (String)req.getAttributeValue(FIELD_ONLINELEARNINGREQUIRED);
		if (type.equalsIgnoreCase("thirdparty") && onlineRequired != null && onlineRequired.equals("No")) {
			LOG.info("Received a thirdparty with online learning == " + onlineRequired + ", skipping");
			return response;
		}
		 */

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
			User user = UserDirectoryService.getUserByEid(CN);
			thisUser = UserDirectoryService.editUser(user.getId());
			LOG.debug(this + " this user useredit right is " + UserDirectoryService.allowAddUser());
			oldType = thisUser.getType();
		} 
		catch (UserNotDefinedException e)
		{
			// If the status is inactive, don't add the user
			if (STATUS_INACTIVE.equals(status)) {
				LOG.info("User " + CN + " doesn't exist on Vula but has status " + status + " so not adding them");
				response.setRequestId(SpmlResponse.RESULT_SUCCESS);
				return response;
			}

			// This user doesn't exist, so create it
			try {
				LOG.debug("About to try adding the user "+ CN);
				newUser = true;
				thisUser = UserDirectoryService.addUser(null,CN);
				LOG.info("created account for user: " + CN);
			}
			catch (UserIdInvalidException in) {
				//should throw out here
				LOG.error("invalid username: " + CN);
				response.setResult(SpmlResponse.RESULT_FAILURE);
				response.setError(SpmlResponse.ERROR_CUSTOM_ERROR);
				response.setErrorMessage("invalid username");
				return response;
			}
			catch (UserAlreadyDefinedException ex) {
				//should throw out here
				LOG.error("User already exists: " + CN);
				response.setResult(SpmlResponse.RESULT_FAILURE);
				response.setError(SpmlResponse.ERROR_CUSTOM_ERROR);
				response.setErrorMessage("user already exists");
				return response;
			}
			catch (UserPermissionException ep) {
				//should throw out here
				LOG.error("no permision to add user " + e);
				response.setResult(SpmlResponse.RESULT_FAILURE);
				response.setError(SpmlResponse.ERROR_CUSTOM_ERROR);
				response.setErrorMessage("No permission to add user");
				return response;
			}
		}
		catch (UserPermissionException e) {
			//should throw out here
			LOG.error("ERROR no permision " + e);
			response.setResult(SpmlResponse.RESULT_FAILURE);
			response.setError(SpmlResponse.ERROR_CUSTOM_ERROR);
			response.setErrorMessage("No permission to edit user");
			return response;
		}
		catch (UserLockedException ul) {
			//should throw out here
			LOG.error("ERROR user locked for editing " + CN);
			//response = new SPMLResponse();
			response.setResult(SpmlResponse.RESULT_FAILURE);
			response.setError(SpmlResponse.ERROR_CUSTOM_ERROR);
			response.setErrorMessage("User is locked for editing");
			return response;
		}

		//try get the profile
		LOG.debug("About to get the profiles");
		userProfile = getUserProfile(CN,"UserMutableType");
		LOG.debug("Got the user profile");
		systemProfile = getUserProfile(CN,"SystemMutableType");
		LOG.debug("Got the system profile");    

		if (systemProfile.getSurname()!=null) { 
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

		if (systemProfile.getGivenName()!=null) {
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

		LOG.debug("this email is: " + thisEmail);

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

		ResourceProperties rp = thisUser.getProperties();
		DateTime dt = new DateTime();
		DateTimeFormatter fmt = ISODateTimeFormat.dateTime();

		// Special case: if the user is inactive, set their email to eid@uct.ac.za
		if (STATUS_INACTIVE.equals(status)) {
			String inactiveMail = thisUser.getEid() + "@uct.ac.za";
			systemProfile.setMail(inactiveMail);
			userProfile.setMail(inactiveMail);
			thisUser.setEmail(inactiveMail);

			// Do we have an inactive flag?
			String deactivated = rp.getProperty(PROP_DEACTIVATED);
			if (deactivated == null) {
				rp.addProperty(PROP_DEACTIVATED, fmt.print(dt));
			}
		}

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
		rp.addProperty(PROP_SPML_LAST_UPDATE, fmt.print(dt));

		LOG.debug("users email profile email is " + userProfile.getMail());

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
			LOG.debug("got type:  " + type + "  and status: " + status);

			// VULA-1006 special case for inactive staff and third party: we set the email to eid@uct.ac.za
			if (TYPE_STUDENT.equals(type) && STATUS_INACTIVE.equals(status)) {
				type = STATUS_INACTIVE;
			} else if (TYPE_STAFF.equals(type) && STATUS_INACTIVE.equals(status)) {
				type = "inactiveStaff";
				thisUser.setEmail(thisUser.getEid() + "@uct.ac.za");
			} else if (TYPE_THIRDPARTY.equals(type) && STATUS_INACTIVE.equals(status)) {
				type = "inactiveThirdparty";
				thisUser.setEmail(thisUser.getEid() + "@uct.ac.za");
			} else	if (STATUS_ADMITTED.equals(status)) {
				type = TYPE_OFFER;
			}

			LOG.debug("got type:  " + type + "  and status: " + status);
			thisUser.setType(type);
			systemProfile.setPrimaryAffiliation(type);
			userProfile.setPrimaryAffiliation(type);
		}

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
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		// Save the user record and user and system profile records
		
		try {
			UserDirectoryService.commitEdit(thisUser);
			sakaiPersonManager.save(systemProfile);			
			sakaiPersonManager.save(userProfile);
		} catch (UserAlreadyDefinedException e1) {
			e1.printStackTrace();
		}			

		// VULA-226 Send new user a notification
		if (sendNotification)
			notifyNewUser(thisUser.getId(), type);

		// For students, update association information: residences, faculty
		
		/*
		 * lets try the course membership
		 * its a comma delimited list in the uctCourseCode attribute
		 * however it might be null - if so ignore and move on
		 * we should only do this if this is a student 
		 */
		if (TYPE_STUDENT.equalsIgnoreCase(originalType)) {
			recordStudentUpdate(thisUser);
			//TODO we need to update the synchs bellow
			//only do this if the user is active -otherwise the student is now no longer registered
			if (! STATUS_INACTIVE.equalsIgnoreCase(status)) { 
				try {

					List<String> checkList = new ArrayList<String>();
					String uctCourses = "";
					/*
					 * offer students go into a special group
					 */
					if ((String)req.getAttributeValue(FIELD_SCHOOL) != null && TYPE_OFFER.equals(type)) {
						String courseCode = (String)req.getAttributeValue(FIELD_SCHOOL) + "_offer_"+ (String)req.getAttributeValue(FIELD_TYPE);
						//we need to figure out which year?
						Calendar cal = new GregorianCalendar();
						if (cal.get(Calendar.MONTH) >= Calendar.AUGUST) {
							//we will do this for next year
							cal.add(Calendar.YEAR, 1);
						}

						SimpleDateFormat yearf = new SimpleDateFormat("yyyy");
						String offerYear = yearf.format(cal.getTime());			

						LOG.info("adding this student to " + courseCode + " in " + offerYear);
						addUserToCourse(CN, courseCode, offerYear, "course");
						checkList.add(courseCode + "," + offerYear);

					} else {
						if ((String)req.getAttributeValue(FIELD_PROGAM)!=null) {
							uctCourses = uctCourses + "," +(String)req.getAttributeValue(FIELD_PROGAM);
							addUserToCourse(CN, (String)req.getAttributeValue(FIELD_PROGAM));
							checkList.add((String)req.getAttributeValue(FIELD_PROGAM));

						}
						if ((String)req.getAttributeValue(FIELD_SCHOOL)!=null) {
							uctCourses = uctCourses + "," +(String)req.getAttributeValue(FIELD_SCHOOL) + "_STUD";
							addUserToCourse(CN, (String)req.getAttributeValue(FIELD_SCHOOL) + "_STUD");
							checkList.add((String)req.getAttributeValue(FIELD_SCHOOL) + "_STUD");
						}
					}


					// Residence information

					SimpleDateFormat yearf = new SimpleDateFormat("yyyy");
					String thisYear = yearf.format(new Date());
					//OK rescode may contain old data
					if (req.getAttributeValue(FIELD_RES_CODE) != null ) {
						String resCode = (String)req.getAttributeValue(FIELD_RES_CODE);
						//if its a wierd no date residence ignore it
						if (resCode.indexOf("*") > 0 ) {
							resCode = resCode.substring(0,resCode.indexOf("*"));
							String year = (String)req.getAttributeValue(FIELD_RES_CODE);
							year = year.substring(year.indexOf("*") + 1,  year.indexOf("-"));
							LOG.info("residence found for: " + resCode +"  year: " + year);
							//If its current add to the list for the sync job
							if (year.equals(thisYear) && residenceIsCurrent((String)req.getAttributeValue(FIELD_RES_CODE))) {
								//uctCourses = uctCourses + "," + resCode;
								checkList.add(resCode);
								this.addUserToCourse(CN, resCode, year, "residence");
							}

						}
					}

					// Academic career
					
					if (req.getAttributeValue(FIELD_ACADEMIC_CARREER) != null) {
						String career = (String)req.getAttributeValue(FIELD_ACADEMIC_CARREER);
						LOG.debug("found academic career: " + career);
						addUserToCourse(CN, career, thisYear, "carrer");
						checkList.add(career);

						//career_faculty
						String facCareer= career + "_" + (String)req.getAttributeValue(FIELD_SCHOOL);
						addUserToCourse(CN, facCareer, thisYear, "carrer");
						checkList.add(facCareer);
					}

					synchCourses(checkList, CN);

				}
				catch (Exception e) {
					//error adding users to course
					e.printStackTrace();
				}
			} else if (STATUS_INACTIVE.equalsIgnoreCase(status)){
				synchCourses(new ArrayList<String>(), CN);
			}
		}
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
			LOG.error("no numberRoutingHelper available for normalizing mobile numbers");
			return mobile;
		}

		return numberRoutingHelper.normalizeNumber(mobile);
	}


	private EmailTemplateService emailTemplateService;

	/**
	 * Send a notification email to a newly added user
	 * @param userId
	 * @param type
	 */
	private void notifyNewUser(String userId, String type) {
		String prefix = "spml.";

		UserEdit ue = null;
		try {
			ue = UserDirectoryService.editUser(userId);
		}
		catch (Exception uex) {
			LOG.warn("failed to get user: " + userId);
			return;
		} 
		
		LOG.info("got user:"  + ue.getDisplayId() + " with email " + ue.getEmail());

		if (ue.getEmail() == null) {
			UserDirectoryService.cancelEdit(ue);
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
				LOG.info("send mail to:" + ue.getEmail() + " subject: " + template.getSubject());
				List<String> recipient = new ArrayList<String>();
				recipient.add(ue.getReference());
				LOG.debug("setting list" + recipient.size());
				emailTemplateService.sendRenderedMessages(prefix + type, recipient, replacementValues, "help@vula.uct.ac.za", "Vula Help");
			} else {
				UserDirectoryService.cancelEdit(ue);
				return;
			}

			try {
				ResourcePropertiesEdit rpe = ue.getPropertiesEdit();
				rpe.addProperty(PROP_SENT_EMAIL, "true");
				UserDirectoryService.commitEdit(ue);
			}
			catch (Exception e) {
				e.printStackTrace();
			}
		}
	}


	/**
	 * SPML DeleteRequest - not handled, so log and ignore 
	 * @param req
	 * @return
	 */
	public SpmlResponse spmlDeleteRequest(SpmlRequest req) {

		LOG.warn("SPML DeleteRequest (not handled)");
		this.logSPMLRequest("DeleteRequest", req.toXml(), null);
		
		SpmlResponse response = null;
		return response;
	} 

	/**
	 * SPML ModifyRequest - not handled, so log and ignore
	 */
	@SuppressWarnings("unchecked")
	public SpmlResponse spmlModifyRequest(ModifyRequest req) {

		LOG.warn("SPML ModifyRequest (not handled)");
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

		LOG.info("SPML BatchRequest");
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
			e.printStackTrace();
		}

		return resp;
	} //end method


	private SessionManager sessionManager;
	public void setSessionManager(SessionManager sm) {
		sessionManager = sm;
	}

	// we'll need to handle login ourselves
	private boolean login(String id,String pw) {
		User user = UserDirectoryService.authenticate(id,pw);
		if ( user != null ) {
			getSessionManager();
			sakaiSession = sessionManager.startSession();
			if (sakaiSession == null)
			{
				return false;
			}
			else
			{
				sakaiSession.setUserId(user.getId());
				sakaiSession.setUserEid(id);
				sessionManager.setCurrentSession(sakaiSession);
				LOG.debug("Logged in as user: " + id + " with internal id of: " + user.getId());
				return true;
			}
		} else {
			LOG.error(this + "login failed for " + id + "using " + pw);
		}
		return false;
	}


	/*
	 * Methods for accessing and editing user profiles
	 * contributed by Nuno Fernandez (nuno@ufp.pt)
	 */	

	/**
	 * Get a user profile record (user or system)
	 */
	private SakaiPerson getUserProfile(String userId, String type) {

		// Uids must be lower case
		userId = userId.toLowerCase();
		this.getSakaiPersonManager();

		Type _type = null;
		if (type.equals("UserMutableType")) {
			setSakaiSessionUser(userId); // switch to that user's session
			_type = sakaiPersonManager.getUserMutableType();
		} else {
			_type = sakaiPersonManager.getSystemMutableType();
		}

		SakaiPerson sakaiPerson = null;
		
		try
		{	
			User user = UserDirectoryService.getUserByEid(userId);
			sakaiPerson = sakaiPersonManager.getSakaiPerson(user.getId(), _type);
			
			// create profile if it doesn't exist
			if(sakaiPerson == null){
				sakaiPerson = sakaiPersonManager.create(user.getId(),_type);
				LOG.info("creating profile for user " + userId + " of type " + _type.getDisplayName());
				
				//we need to set the privacy
				sakaiPerson.setHidePrivateInfo(Boolean.valueOf(true));
				sakaiPerson.setHidePublicInfo(Boolean.valueOf(false));
			}
		}	
		catch(Exception e){
			LOG.error("Unknown error occurred in getUserProfile(" + userId + "): " + e.getMessage());
			e.printStackTrace();
		}

		if (type.equals("UserMutableType")) {
			//return to the admin user
			setSakaiSessionUser(SPML_USER);
		}

		return sakaiPerson;
	}

	/**
	 * Set the session to the new user
	 */
	private synchronized void setSakaiSessionUser(String id) {
		try {
			User user = UserDirectoryService.getUserByEid(id);
			sakaiSession.setUserId(user.getId());
			sakaiSession.setUserEid(id);
		}
		catch (Exception e)
		{
			LOG.error(this +" " + e);
		}
	} 

	/**
	 * Add the user to the SPML_UPDATED_USERS table as a trigger to the external Peoplesoft integration script
	 * @param u
	 */
	private void recordStudentUpdate(User u) {
		getSqlService();

		// TODO Sql injection
		String sql = "select userEid from " + TBL_UPDATED_USERS + " where userEid = '" + u.getEid() + "'";
		List<String> result = m_sqlService.dbRead(sql);
		
		if (result == null || result.size() == 0) {
			sql = "insert into " + TBL_UPDATED_USERS + " (userEid, dateQueued) values (?, ?)";
			Object[] fields = new Object[] {
					u.getEid(),
					new Date()
			};
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
			e.printStackTrace();
		}

	}


	/**
	 * Add the user to a course
	 */
	private void addUserToCourse(String userId, String courseCode) {
		addUserToCourse(userId, courseCode, null, null);
	}

	/**
	 * Add the user to a course
	 * @param userId
	 * @param courseCode
	 * @param term
	 * @param setCategory
	 */
	private void addUserToCourse(String userId, String courseCode, String term, String setCategory) {
		LOG.debug("addUserToCourse(" + userId +", " + courseCode + "," + term + "," + setCategory + ")");

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
					setCategory = "degree";
				} else {
					setId = courseCode.substring(0,3);
					setCategory = "Department";
				}
			} else {
				setId = courseCode;
			}

			if (setCategory.equalsIgnoreCase("residence")) {
				role = "Participant";
			}

			String courseEid = null;
			if (term == null) {
				SimpleDateFormat yearf = new SimpleDateFormat("yyyy");
				String thisYear = yearf.format(new Date());
				courseEid = getPreferredSectionEid(courseCode, thisYear);
				term = courseEid.substring(courseEid.indexOf(",") + 1);
				LOG.debug("term is " + term);
			} else {
				//we already have a specific term
				courseEid = courseCode + "," +term;
			}

			// before 2011 we dropped extended course codes
			int numericTerm = Integer.valueOf(term).intValue();
			if (courseEid.length()==11 && numericTerm < 2012)
			{
				courseEid = courseEid.substring(0,8);
			}

			// do we have an academic session?
			if (!cmService.isAcademicSessionDefined(term)) {
				Calendar cal = Calendar.getInstance();
				cal.set(new Integer(term).intValue(), 1, 1);
				Date start =  cal.getTime();
				cal.set(new Integer(term).intValue(), Calendar.DECEMBER, 30);
				Date end = cal.getTime();
				courseAdmin.createAcademicSession(term, term, term, start, end);
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
				LOG.info("creating course offering for " + courseCode + " in year " + term);
				EmailService.send("help-team@vula.uct.ac.za", "help-team@vula.uct.ac.za", 
						"[CM]: new course created on vula: " + courseEid, 
						"[CM]: new course created on vula: " + courseEid, null, null, null);
				
				// If this is being created by SPML, it is current now
				Date startDate = new Date();

				// Use the term date
				Calendar cal2 = Calendar.getInstance();
				cal2.set(Calendar.DAY_OF_MONTH, 31);
				cal2.set(Calendar.MONTH, Calendar.OCTOBER);
				if (term !=null) {
					cal2.set(Calendar.YEAR, Integer.valueOf(term));
				}

				// If this is a residence, the end date is later.
				if (setCategory.equalsIgnoreCase("residence")) {
					cal2.set(Calendar.DAY_OF_MONTH, 19);
					cal2.set(Calendar.MONTH, Calendar.NOVEMBER);
				}

				Date endDate = cal2.getTime();
				LOG.debug("got cal:" + cal2.get(Calendar.YEAR) + "/" + cal2.get(Calendar.MONTH) + "/" + cal2.get(Calendar.DAY_OF_MONTH));
				
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
				courseAdmin.createSection(courseEid, courseEid, "description", "course", null, courseEid, enrollmentSet.getEid());
			} else {
				Section section = cmService.getSection(courseEid);
				// Check the section has a properly defined Enrollment set
				if (section.getEnrollmentSet() == null) {
					EnrollmentSet enrolmentSet = cmService.getEnrollmentSet(courseEid);
					section.setEnrollmentSet(enrolmentSet);
					section.setCategory("course");
					courseAdmin.updateSection(section);
				}
			}

			LOG.info("adding student " + userId + " to " + courseEid);
			courseAdmin.addOrUpdateSectionMembership(userId, role, courseEid, "enrolled");
			courseAdmin.addOrUpdateEnrollment(userId, courseEid, "enrolled", "NA", "0");

			// now add the user to a section of the same name
			// TODO this looks like duplicate logic

			try {
				cmService.getSection(courseEid);
			} 
			catch (IdNotFoundException id) {
				//create the CO
				//create enrolmentset
				courseAdmin.createEnrollmentSet(courseEid, courseEid, "description", "category", "defaultEnrollmentCredits", courseEid, null);
				LOG.info("creating Section for " + courseCode + " in year " + term);
				getCanonicalCourse(courseCode);
				courseAdmin.createSection(courseEid, courseEid, "someDescription","course",null,courseEid,courseEid);
			}
			courseAdmin.addOrUpdateSectionMembership(userId, role, courseEid, "enrolled");
		}
		catch(Exception e) {
			e.printStackTrace();
		}
	}


	private String getPreferredSectionEid(String courseCode, String term) {
		/* TODO we need the get the actice CM for this course
		 * This will involve refactoring code below
		 */
		String courseEid = null;
		LOG.debug("about to get sections in " + courseCode);
		List<CourseOffering> sections  = cmService.findActiveCourseOfferingsInCanonicalCourse(courseCode);
		LOG.debug("got  " + sections.size() +",  sections");
		if (sections.size() > 0) {
			//if there are multiple courses we will add them to the one in the later accademic year
			CourseOffering co = getPreferredSection(sections);
			courseEid = co.getEid();
			LOG.debug("Found active course: " + courseEid);
		} else {
			//use the not found info from bellow
			//does the 
			courseEid = courseCode + "," +term;
		}
		return courseEid;
	}

	
	private CourseOffering getPreferredSection(List<CourseOffering> sections) {

		if (sections.size() == 1) {
			return sections.get(0);
		}

		CourseOffering preferedOffering = null;
		//we want the one in the later year
		for (int i =0; i < sections.size(); i++) {
			CourseOffering co = sections.get(i);
			if (preferedOffering == null) {
				preferedOffering = co;
			} else {
				AcademicSession preferedSection = preferedOffering.getAcademicSession();
				AcademicSession session = co.getAcademicSession();
				if (session.getStartDate().after(preferedSection.getStartDate())) {
					preferedOffering = co;
				}
			}
		}

		LOG.info("found preferred offering of " + preferedOffering.getEid());
		return preferedOffering;
	}


	/**
	 * Remove user from old courses
	 * @param uctCourse
	 * @param userEid
	 */
	private void synchCourses(List<String> uctCourse, String userEid){
		LOG.debug("Checking enrollments for " + userEid);
		SimpleDateFormat yearf = new SimpleDateFormat("yyyy");
		String thisYear = yearf.format(new Date());

		courseAdmin = getCourseAdmin();
		cmService = getCourseManagementService();

		// VULA-1256 we need all enrolled sets that are current or future
		Set<EnrollmentSet> enrolled = getCurrentFutureEnrollments(userEid);

		// TODO we need to filter out all course groups
		enrolled = filterCourseList(enrolled);
		
		//cmService.findCurrentlyEnrolledEnrollmentSets(userEid);
		Iterator<EnrollmentSet> coursesIt = enrolled.iterator();
		LOG.debug("got list of enrollment set with " + enrolled.size() +  " checklist contains " + uctCourse.size());
		List<String> finalCourses = new ArrayList<String>();
		for (i = 0; i < uctCourse.size(); i++) {
			String thisCourse = uctCourse.get(i);
			if (LOG.isDebugEnabled()) {
				LOG.debug("courseList contains: " + thisCourse);
			}
			//we need a fully Qualified id for the section
			String newSection = getPreferredSectionEid(thisCourse, thisYear);
			finalCourses.add(newSection.toUpperCase());
		}

		//TODO this could be more elegantly done by upper-casing the contents of uctCourse
		while(coursesIt.hasNext()) {
			EnrollmentSet eSet = (EnrollmentSet)coursesIt.next();
			String courseEid =  eSet.getEid();
			LOG.debug("got section: " + courseEid);
			boolean found = false;
			if (finalCourses.contains(courseEid)) {
				found = true;
			} else if (finalCourses.contains(courseEid + "," + thisYear)) {
				found = true;
			}

			if (!found) {
				for (int i =0; i < finalCourses.size(); i++ ) {
					String thisEn = (String)finalCourses.get(i) + "," + thisYear;
					if (thisEn.equalsIgnoreCase(courseEid)) {
						found = true;
					}

				}
				if (!found) {
					for (int i =0; i < finalCourses.size(); i++ ) {
						String thisEn = (String)finalCourses.get(i);
						if (thisEn.equalsIgnoreCase(courseEid)) {
							found = true;
						}

					}					
				}
			}
			if (!found && !doSection(courseEid)) {
				LOG.info("removing user from " + courseEid);
				courseAdmin.removeCourseOfferingMembership(userEid, courseEid);
				courseAdmin.removeSectionMembership(userEid, courseEid);
				courseAdmin.removeEnrollment(userEid, courseEid);
			}
		}

	}


	private Set<EnrollmentSet> filterCourseList(Set<EnrollmentSet> enroled) {
		Set<EnrollmentSet> ret = new HashSet<EnrollmentSet>();

		Iterator<EnrollmentSet> it = enroled.iterator();
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
		String eid = section;

		if (eid.indexOf("_STUD") > 0) {
			LOG.debug(eid + " looks like a faculty group");
			return true;
		}

		if (eid.length() == "PSY307SSUP,2010".length()) {
			LOG.debug("we don't work with " + eid);
			return false;
		} else	if (eid.length() == "PSY307S,2010".length()) {
			LOG.debug("we don't work with " + eid);
			return true;
		}

		LOG.debug("we work with " + eid);

		return true;
	}

	private Set<EnrollmentSet> getCurrentFutureEnrollments(String userEid) {
		LOG.debug("getCurrentFutureEnrollments(" + userEid +")");
		Set<EnrollmentSet> ret = new HashSet<EnrollmentSet>();

		Set<Section> sections = cmService.findEnrolledSections(userEid);
		Iterator<Section> it = sections.iterator();
		
		while (it.hasNext()) {
			Section section = it.next();
			CourseOffering courseOffering = cmService.getCourseOffering(section.getCourseOfferingEid());
			// we may have old ones without dates
			if (courseOffering.getStartDate() == null || courseOffering.getEndDate() == null) {
				LOG.debug("Course offering " + courseOffering.getEid() + " is missing start or end date");
				continue;
			}

			// is it current
			if (new Date().after(courseOffering.getStartDate()) && new Date().before(courseOffering.getEndDate())) {
				LOG.debug("offering " + courseOffering.getEid() + " is current");
				ret.add(cmService.getEnrollmentSet(section.getEid()));
			} else if (new Date().before(courseOffering.getStartDate()) ) {
				LOG.debug("offering " + courseOffering.getEid() + " is in the future");
				ret.add(cmService.getEnrollmentSet(section.getEid()));
			} else {
				LOG.debug("not checking " + courseOffering.getEid() + " start: " + courseOffering.getStartDate() + ", end: " + courseOffering.getEndDate());
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
			LOG.info("creating canonicalcourse " + courseCode);
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
			LOG.debug("got org unit of " + (String)result.get(0));
			return (String)result.get(0);
		} else {
			LOG.warn("Unknown org code of " + modOrgUnit + " received");
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

		LOG.debug("checking if this resCode is current: " + resCode);

		/* rescode is of the form RES*YYYY-MM-DD*YYYY-MM-DD
		 * we need to parse these to dates
		 */
		try {
			String endDate = resCode.substring(15);
			DateFormat df = new SimpleDateFormat("yyyy-MM-dd");
			Date end = df.parse(endDate);

			if (end.before(new Date())) {
				LOG.debug("residence end date is in the past");
				return false;
			}

		} catch (ParseException e) {
			e.printStackTrace();
		} catch (Exception ex) {
			ex.printStackTrace();
		}

		return true;
	}

} //end class
