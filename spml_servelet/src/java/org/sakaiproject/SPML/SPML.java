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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
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
import org.sakaiproject.coursemanagement.api.CourseManagementAdministration;
import org.sakaiproject.coursemanagement.api.CourseManagementService;
import org.sakaiproject.coursemanagement.api.EnrollmentSet;
import org.sakaiproject.coursemanagement.api.exception.IdNotFoundException;
import org.sakaiproject.db.api.SqlService;
import org.sakaiproject.email.cover.EmailService;
import org.sakaiproject.emailtemplateservice.model.RenderedTemplate;
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
import org.sakaiproject.util.StringUtil;

public class SPML implements SpmlHandler  {

	//Atribute mappings to map SPML attributes to Sakai attributs


	/*
	 *  Field name mappings
	 */
	private static final String FIELD_CN = "CN";
	private static final String FIELD_SURNAME = "Surname";
	private static final String FIELD_GN = "Given Name";
	private static final String FIELD_PN = "preferredName";
	private static final String FIELD_MAIL = "Email";
	private static final String FIELD_TYPE = "eduPersonPrimaryAffiliation";
	private static final String FIELD_MEMBERSHIP = "uctCourseCode";
	private static final String FIELD_SCHOOL ="uctFaculty";
	private static final String FIELD_MOBILE = "mobile";
	private static final String FIELD_PROGAM = "uctProgramCode";
	private static final String FIELD_HOMEPHONE ="homePhone";
	private static final String FIELD_OU ="OU";
	private static final String FIELD_DOB="DOB";
	
	private static final String FIELD_RES_CODE="uctResidenceCode";
	private static final String FIELD_ORG_DECR = "Description";
	private static final String FIELD_TITLE = "uctPersonalTitle";
	private static final String FIELD_ACADEMIC_CARREER = "uctAcademicCareer";

	private static final String TYPE_STUDENT = "student";
	private static final String TYPE_STAFF = "staff";
	private static final Object TYPE_THIRDPARTY = "thirdparty";


	private static final String STATUS_ACTIVE = "Active";
	private static final String STATUS_INACTIVE = "Inactive";
	private static final String STATUS_ADMITTED = "Admitted";
	//change this to the name of your campus
	
	private static final String SPML_USER = ServerConfigurationService.getString("spml.user", "admin");
	private static final String SPML_PASSWORD = ServerConfigurationService.getString("spml.password", "admin");
	


	private static final String PROPERTY_SENTEMAIL = "uctNewMailSent";


	/*
	 *  Objects that will contain info about this user
	 */
	//private UserDirectoryService UserDirectoryService = new UserDirectoryService();



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
	 * Set the "SOAP action name" which may be requried by
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


	//public String requestIp = ( (javax.servlet.http.HttpServletRequest) FacesContext.getCurrentInstance().getExternalContext().getRequest()).getRemoteAddr();
	public String requestIp = "0.0.0.0"; 
	//////////////////////////////////////////////////////////////////////
	//
	//  Setters
	//
	/////////////////////////////////////////////////////////////////
	public void init()
	{
		//EMPTY
		LOG.info (this + " init()");	
	}

	public void destroy() 
	{

		LOG.info(this + " destroy()");

	}

	//the sakaiSession object
	public Session sakaiSession;




	/*
	 * Setup the Sakai person manager and the agentgroup manager
	 * contibuted by Nuno Fernandez (nuno@ufp.pt)
	 * 
	 */
	private SakaiPersonManager sakaiPersonManager;
	//private AgentManager agentGroupManager = new AgentManager();

	public void setSakaiPersonManager(SakaiPersonManager spm) {
		sakaiPersonManager = spm;

	}

	private SakaiPersonManager getSakaiPersonaManager() {
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


		//this.logSPMLRequest("Unknown",req.toXml());
		try {

			//we need to login
			//this will need to be changed - login can be sent via attributes to the object?
			LOG.debug("About to login");
			boolean sID = login(SPML_USER,SPML_PASSWORD);
			if (sID == false) {
				resp.setError("Login failure");
				resp.setResult("failure");
				return resp;
			}
			//get the session

			//HttpServletRequest request = (HttpServletRequest) ComponentManager.get(HttpServletRequest.class.getName());
			//LOG.info("lets try this" + request.getRemoteAddr());



			if (req instanceof AddRequest) {
				AddRequest uctRequest = (AddRequest)req;
				try {
					resp = spmlAddRequest(uctRequest);
				}
				catch (Exception e) {
					e.printStackTrace();

				}
			} else if (req instanceof ModifyRequest) {
				LOG.info("SPMLRouter identified Modifyreq");
				ModifyRequest uctRequest = (ModifyRequest)req;
				resp = spmlModifyRequest(uctRequest);
			} else if (req instanceof DeleteRequest) {
				LOG.info("SPMLRouter identified delete request");
				DeleteRequest uctRequest = (DeleteRequest)req;
				resp = spmlDeleteRequest(uctRequest);
			}  else if (req instanceof BatchRequest) {
				LOG.info("SPMLRouter identified batch request");
				BatchRequest uctRequest = (BatchRequest)req;
				resp = spmlBatchRequest(uctRequest);

			} else {
				LOG.error("Method not implemented");
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

	/*
	 *  the actual SPM requests
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
		CN =(String)req.getAttributeValue(FIELD_CN);
		LOG.info("SPML Webservice: Received AddRequest for user "+ CN);
		SpmlResponse response = req.createResponse();
		//we have had 1 null CN so this should be thrown
		if (CN == null) {
			LOG.error("ERROR: invalid username: " + CN);
			response.setResult(SpmlResponse.RESULT_FAILURE);
			response.setError(SpmlResponse.ERROR_CUSTOM_ERROR);
			response.setErrorMessage("invalid username");
			//LOG.info(req.toXml());
			logSPMLRequest("Addrequest",req.toXml(), null);
			return response;			

		}
		CN = CN.toLowerCase();
		//LOG.info(req.toXml());
		logSPMLRequest("Addrequest",req.toXml(),CN);
		
		
		if (req.getAttributeValue(FIELD_PN)!=null)
			GN = (String)req.getAttributeValue(FIELD_PN);
		else
			GN = (String)req.getAttributeValue(FIELD_GN);

		String LN = (String)req.getAttributeValue(FIELD_SURNAME);
		LN = LN.trim();
		String thisEmail = (String)req.getAttributeValue(FIELD_MAIL);
		//always lower case


		String thisTitle = (String)req.getAttributeValue(FIELD_TITLE);

		String type = (String)req.getAttributeValue(FIELD_TYPE);
		String origionalType = type;
		//If eduPerson is null reject
		if (type == null || type.equals("")) {
			LOG.error("ERROR: no eduPersonPrimaryAffiliation: " + CN);
			response.setResult(SpmlResponse.RESULT_FAILURE);
			response.setError(SpmlResponse.ERROR_CUSTOM_ERROR);
			response.setErrorMessage("no eduPersonPrimaryAffiliation");
			return response;
		}

		type = type.toLowerCase();
		String status = STATUS_ACTIVE;
		if (TYPE_STUDENT.equals(type))
			status = (String)req.getAttributeValue("uctStudentStatus");
		else if (TYPE_STAFF.equals(type))
			status = (String)req.getAttributeValue("employeeStatus");
		else if (TYPE_THIRDPARTY.equals(type))
			status = (String)req.getAttributeValue("ucttpstatus");

		LOG.info("user status is: " + status);
		//for staff this could be null
		if (status == null && TYPE_STUDENT.equals(type))
		{
			status = STATUS_INACTIVE;
		}
		
		//if this is a thirparty check the online learning required field
		/*
		String onlineRequired = (String)req.getAttributeValue(FIELD_ONLINELEARNINGREQUIRED);
		if (type.equalsIgnoreCase("thirdparty") && onlineRequired != null && onlineRequired.equals("No")) {
			//return 
			LOG.info(" Received a thirdparty with online learning == " + onlineRequired + ", skipping");
			return response;

		}
		 */


		String mobile = (String)req.getAttributeValue(FIELD_MOBILE);
		if (mobile == null ) {
			mobile ="";
		} else {
			mobile = fixPhoneNumber(mobile);
		}


		String homeP = (String)req.getAttributeValue(FIELD_HOMEPHONE);
		if (homeP == null ) {
			homeP ="";
		} else {
			homeP = fixPhoneNumber((String)req.getAttributeValue(FIELD_HOMEPHONE));
		}

		String orgUnit = (String)req.getAttributeValue(FIELD_OU);
		String orgCode = null;
		if (orgUnit == null ) {
			orgUnit="";
		} else {
			orgCode = getOrgCodeById(orgUnit);
		}


		String orgName = (String)req.getAttributeValue(FIELD_ORG_DECR);
		if (orgName == null )
			orgName = "";

		boolean newUser = false;
		boolean sendNotification = false;

		try {
			//rather lets get an object
			User user = UserDirectoryService.getUserByEid(CN);
			thisUser = UserDirectoryService.editUser(user.getId());
			LOG.debug(this + " this user useredit right is " + UserDirectoryService.allowAddUser());
			LOG.debug(this + " Got UserEdit");
		} 
		catch (UserNotDefinedException e)
		{
			//this user doesnt exist create it
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
				LOG.error("ERROR: UserAlready exists: " + CN);
				response.setResult(SpmlResponse.RESULT_FAILURE);
				response.setError(SpmlResponse.ERROR_CUSTOM_ERROR);
				response.setErrorMessage("user already exists");
				return response;
			}
			catch (UserPermissionException ep) {
				//should throw out here
				LOG.error("ERROR no permision to add user " + e);
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
			if (userProfile.getMail() != null && !systemMail.equals(userProfile.getMail()) && !userProfile.getMail().equals("")) {
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
			if (thisUser.getEmail() == null || "".equals(thisUser.getEmail())) {
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
			if (TYPE_STUDENT.equals(type) && STATUS_INACTIVE.equals(status) && newUser) {
				type = "offer";  
			} else if (TYPE_STUDENT.equals(type) && STATUS_INACTIVE.equals(status)) {
				type = STATUS_INACTIVE;
			} else if (TYPE_STAFF.equals(type) && STATUS_INACTIVE.equals(status)) {
				type = "inactiveStaff";
			} else if (TYPE_THIRDPARTY.equals(type) && STATUS_INACTIVE.equals(status)) {
				type = "inactiveThirdparty";
			} else	if (STATUS_ADMITTED.equals(status)) {
				type = "offer";
			}
			
			LOG.debug("got type:  " + type + "  and status: " + status);
			thisUser.setType(type);
			systemProfile.setPrimaryAffiliation(type);
			userProfile.setPrimaryAffiliation(type);

		}

		//set the profile Common name
		systemProfile.setCommonName(CN);
		userProfile.setCommonName(CN);

		//this last one could be null
		String systemMobile = systemProfile.getMobile();
		String systemOrgCode = systemProfile.getOrganizationalUnit();
		String systemOrgUnit = systemProfile.getDepartmentNumber();
		String systemNormalizedMobile = systemProfile.getNormalizedMobile();
		//set up the strings for user update these will be overwriten for changed profiles


		String modMobile = mobile;
		String modOrgUnit = orgUnit;
		String modOrgCode = orgCode;
		

		//if the user surname != system surname only update the system 


		String userMobileNormalized = normalizeMobile(userProfile.getMobile());
		String newNuberNormalized = normalizeMobile(modMobile);

		if (systemMobile != null && systemNormalizedMobile == null) {
			systemNormalizedMobile = normalizeMobile(systemMobile);
		}
		
		if (systemMobile != null) {
			if (!systemNormalizedMobile.equals(userMobileNormalized)) {
				systemProfile.setMobile(modMobile);
				systemProfile.setNormalizedMobile(newNuberNormalized);
			} else {
				systemProfile.setMobile(modMobile);
				userProfile.setMobile(modMobile);
				systemProfile.setNormalizedMobile(newNuberNormalized);
				userProfile.setNormalizedMobile(newNuberNormalized);

			}
		} else if (systemMobile == null && modMobile != null) {
			systemProfile.setMobile(modMobile);
			userProfile.setMobile(modMobile);
			systemProfile.setNormalizedMobile(normalizeMobile(mobile));
			userProfile.setNormalizedMobile(normalizeMobile(mobile));
		}


		/*this is actua;ly the department number
		 * we need to get the 3 letter code to set the org unit
		 * 
		 * 
		 */
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

		//the 3 letter code
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


		/* causing privacy concerns)

			if (systemHomeP != null) {
				if (!systemHomeP.equals(userProfile.getHomePhone())) {
					systemProfile.setHomePhone(modHomeP);
				} else {
					systemProfile.setHomePhone(modHomeP);
					userProfile.setHomePhone(modHomeP);
				}
			} else if (systemHomeP == null && modHomeP != null) {
				systemProfile.setHomePhone(modHomeP);
				userProfile.setHomePhone(modHomeP);				
			}

		 */
		// set the DOB -no method at the moment
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


		//save the user
		try {
			UserDirectoryService.commitEdit(thisUser);
			//save the profiles
			//save the profiles

			sakaiPersonManager.save(systemProfile);
			//setSakaiSessionUser(CN);
			sakaiPersonManager.save(userProfile);
			//setSakaiSessionUser(SPML_USER);  // get back the admin session
		} catch (UserAlreadyDefinedException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}			


		/*
		 * Send new user a notification
		 */
		if (sendNotification)
			notifyNewUser(thisUser.getId(), type);

		/*
		 * lets try the course membership
		 * its a comma delimited list in the uctCourseCode attribute
		 * however it might be null - if so ignore and move on
		 * we should only do this if this is a student 
		 */
		if (TYPE_STUDENT.equalsIgnoreCase(origionalType)) {
			//only do this if the user is active -otherwise the student is now no longer registered
			if (! STATUS_INACTIVE.equalsIgnoreCase(status)) { 
				try {
					String uctCourses =null;
					uctCourses = (String)req.getAttributeValue(FIELD_MEMBERSHIP);

					List<String> checkList = new ArrayList<String>();

					/*
					 * offer students go into a special group
					 */
					if ((String)req.getAttributeValue(FIELD_SCHOOL) != null && "offer".equals(type)) {
						String courseCode = (String)req.getAttributeValue(FIELD_SCHOOL) + "_offer_"+ (String)req.getAttributeValue(FIELD_TYPE);
						LOG.info("adding this student to " + courseCode);
						addUserToCourse(CN, courseCode);
						checkList.add(courseCode);
						
					} else if (uctCourses!=null && uctCourses.length()>0) {

						if ((String)req.getAttributeValue(FIELD_PROGAM)!=null) {
							uctCourses = uctCourses + "," +(String)req.getAttributeValue(FIELD_PROGAM);
						}
						if ((String)req.getAttributeValue(FIELD_SCHOOL)!=null) {
							uctCourses = uctCourses + "," +(String)req.getAttributeValue(FIELD_SCHOOL) + "_STUD";
						}

						String[] uctCourse =  StringUtil.split(uctCourses, ",");
						LOG.info(" got " + uctCourse.length + " courses");
						for (int ai = 0; ai < uctCourse.length; ai ++ ) {
							//System.out.println("got a coursecode " + uctCourse[ai]);
							String course = uctCourse[ai].trim();
							if (course.length()==11)
							{
								course = course.substring(0,8);
							}
							LOG.info("adding this student to " + course);
							checkList.add(course);
							addUserToCourse(CN,course);

						}
					} //end if courseList not null

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
								uctCourses = uctCourses + "," + resCode;
								checkList.add(resCode);

							}
							this.addUserToCourse(CN, resCode, year, "residence");
						}
					}


					//academic career
					if (req.getAttributeValue(FIELD_ACADEMIC_CARREER) != null) {
						String career = (String)req.getAttributeValue(FIELD_ACADEMIC_CARREER);
						LOG.debug("found accademinc Career: " + career);
						addUserToCourse(CN, career, thisYear, "carrer");
						checkList.add(career);

						//career_faculty
						String facCareer= career + "_" + (String)req.getAttributeValue(FIELD_SCHOOL);
						addUserToCourse(CN, facCareer, thisYear, "carrer");
						checkList.add(facCareer);

					}

					//now synch 
					synchCourses(checkList, CN);



				}
				catch (Exception e) {
					//Nothing to do...
					//error adding users to course
					e.printStackTrace();

				}
			} else if (STATUS_INACTIVE.equalsIgnoreCase(status)){
				synchCourses(new ArrayList<String>(), CN);
			}
		}
		return response;
	} 
	


	private String normalizeMobile(String mobile) {
		numberRoutingHelper = getNumberRoutingHelper();
		if (numberRoutingHelper == null) {
			LOG.error("no numberrouting helper!");
			return mobile;
		}
		
		return numberRoutingHelper.normalizeNumber(mobile);
	}




	private EmailTemplateService emailTemplateService;

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

		if ( rp.getProperty(PROPERTY_SENTEMAIL) == null) {


			Map<String, String> replacementValues = new HashMap<String, String>();
			replacementValues.put("userFirstName", ue.getFirstName());
			replacementValues.put("userLastName", ue.getLastName());
			replacementValues.put("userEmail", ue.getEmail());

			emailTemplateService = getEmailTemplateService();

			RenderedTemplate template = emailTemplateService.getRenderedTemplateForUser(prefix + type, ue.getReference() , replacementValues);
			if (template != null) {
				LOG.info("send mail to:" + ue.getEmail() + " subject: " + template.getSubject());
				EmailService.send("help@vula.uct.ac.za", ue.getEmail(), template.getSubject(), template.getMessage(), null, null, null);
			} else {
				UserDirectoryService.cancelEdit(ue);
				return;
			}

			try {

				ResourcePropertiesEdit rpe = ue.getPropertiesEdit();
				rpe.addProperty(PROPERTY_SENTEMAIL, "true");
				UserDirectoryService.commitEdit(ue);
			}
			catch (Exception e) {
				e.printStackTrace();
			}

		}
	}



	public SpmlResponse spmlDeleteRequest(SpmlRequest req) {
		LOG.info("SPML Webservice: Received DeleteRequest "+req);
		this.logSPMLRequest("DeleteRequest",req.toXml(), null);
		/*
		 * User user = UserDirectoryService.getUserByEid(CN);
		 * thisUser = UserDirectoryService.editUser(user.getId());
		 *  //try get the profile
			userProfile = getUserProfile(CN,"UserMutableType");
			systemProfile = getUserProfile(CN,"SystemMutableType");
			SakaiPersonManager.delete(userProfile);
			SakaiPersonManager.delete(systemProfile);
		 *  UserDirectoryService.removeUser(thisUser);
		 */

		SpmlResponse response = null;
		return response;
	} 

	@SuppressWarnings("unchecked")
	public SpmlResponse spmlModifyRequest(ModifyRequest req) {
		LOG.info("SPML Webservice: Received DeleteRequest "+req);

		this.logSPMLRequest("ModifyRequest",req.toXml(), null);
		//List attrList = req.getAttributes();
		/* Attributes are:
	       objectclass
	       CN
	       Surname
	       Full Name
	       Given Name
	       Initials
	       nspmDistributionPassword
		 */
		String CN = (String)req.getIdentifierString();
		LOG.info("got mods for " + CN);
		//we now need to find what has changed 
		//first we need the exisiting values

		String GN;
		String LN;
		String thisEmail; 
		String type;

		try {
			User thisUser = UserDirectoryService.getUser(CN);

			GN = thisUser.getFirstName();
			LN = thisUser.getLastName();
			thisEmail = thisUser.getEmail();

		}
		catch (Exception e) {
			LOG.error(e);
		}
		try {
			List<Modification> mods = req.getModifications();
			LOG.info("got " + mods.size() + " modifications");
			for (int i = 0; i <mods.size(); i++) {

				LOG.info(mods.get(i));
				Modification mod = (Modification)mods.get(i);
				LOG.info(mod.getName());
				//map the SPML names to their atributes
				if (mod.getName().equals(FIELD_GN)) {
					GN = (String)mod.getValue();
				} else if (mod.getName().equals(FIELD_SURNAME)) {
					LN = (String)mod.getValue();
				} else if (mod.getName().equals(FIELD_MAIL)) {
					thisEmail = (String)mod.getValue();
				}



			}
		}
		catch (Exception e) {
			e.printStackTrace();
		}

		
		SpmlResponse response = null;

		try {


			//we need to login
			//this will need to be changed - login can be sent via attributes to the object?
			login("admin","admin");
			//this methd no longer exits
			//String addResp = changeUserInfo(sID, CN, GN, LN, thisEmail, type, passwd);

			response = req.createResponse();

		}
		catch(Exception e) {
			e.printStackTrace();
		}

		return response;
	} 		

	@SuppressWarnings("unchecked")
	public SpmlResponse spmlBatchRequest(BatchRequest req) {
		LOG.info("received SPML Batch Request");
		SpmlResponse resp = null;

		try {
			//we need to iterate throug through the units in the batch

			//get a list of the actual methods
			List<SpmlRequest> requestList = req.getRequests(); 
			for (int i =0 ; i < requestList.size(); i++) {
				//each item in the list these should be a spml object...
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

				//create the responce	
				resp = req.createResponse();
			} //end the for loop

		}
		catch (Exception e) {
			e.printStackTrace();
		}

		return resp;
	} //end method

	/*
	 *Private internal methods
	 *Using 
	 *
	 */


	private SessionManager sessionManager;
	public void setSessionManager(SessionManager sm) {
		sessionManager = sm;
	}

	//well need to handle login ourselves
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
	 *  
	 */	


	/*
	 * get a user proString escapeBody = body.replaceAll("'","''");file for a user
	 */
	private SakaiPerson getUserProfile(String userId, String type) {

		//Uid's must be lower case
		userId = userId.toLowerCase();

		this.getSakaiPersonaManager();

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
		if (type.equals("UserMutableType")) {
			setSakaiSessionUser(SPML_USER);
		}
		return sakaiPerson;
	}

	/*
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




	/*
	 * Log the request
	 */
	
	private void logSPMLRequest(String type, String body, String CN) {
		try {
			String escapeBody = StringEscapeUtils.escapeSql(body);
			if (CN == null) {
				CN = "null";
			}
			String statement = "insert into spml_log (spml_type,spml_body, ipaddress, userEid) values ('" + type +"','" + escapeBody + "','" + requestIp + "','" + StringEscapeUtils.escapeSql(CN)
			+"')";
			//LOG.info(this + "SQLservice:" + m_sqlService);
			getSqlService();
			m_sqlService.dbWrite(statement);
	
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		
	}


	/*
	 * add the user to a course
	 * 
	 */
	private void addUserToCourse(String userId, String courseCode) {
		SimpleDateFormat yearf = new SimpleDateFormat("yyyy");
		String thisYear = yearf.format(new Date());

		addUserToCourse(userId,courseCode, thisYear, null);
	}

	private void addUserToCourse(String userId, String courseCode, String term, String setCategory) {



		try {


			courseCode = courseCode.toUpperCase().trim();
			courseAdmin = getCourseAdmin();
			cmService =getCourseManagementService();
			if (courseCode == null || courseCode.length() == 0) {
				return;
			}
			//does the 
			String courseEid = courseCode + "," +term;
			//is there a cannonical course?
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

			String role = "Student";
			if (setCategory.equalsIgnoreCase("residence")) {
				role = "Participant";
			}


			//do we have a accedemic session?
			if (!cmService.isAcademicSessionDefined(term)) {
				Calendar cal = Calendar.getInstance();
				cal.set(new Integer(term).intValue(), 1, 1);
				Date start =  cal.getTime();
				cal.set(new Integer(term).intValue(), Calendar.DECEMBER, 30);
				Date end = cal.getTime();
				courseAdmin.createAcademicSession(term, term, term, start, end);
			}
			//does the course set exist?

			if (!cmService.isCourseSetDefined(setId)) 
				courseAdmin.createCourseSet(setId, setId, setId, setCategory, null);

			if (!cmService.isCanonicalCourseDefined(courseCode)) {
				courseAdmin.createCanonicalCourse(courseCode, courseCode, courseCode);
				courseAdmin.addCanonicalCourseToCourseSet(setId, courseCode);
			}


			if (!cmService.isCourseOfferingDefined(courseEid)) {
				LOG.info("creating course offering for " + courseCode + " in year " + term);
				EmailService.send("help-team@vula.uct.ac.za", "help-team@vula.uct.ac.za", "[CM]: new course created on vula: " + courseEid, "[CM]: new course created on vula: " + courseEid, null, null, null);
				//if this is being created by SPML its current now
				Date startDate = new Date();
				
				//use the term date
				Calendar cal2 = Calendar.getInstance();
				cal2.set(Calendar.DAY_OF_MONTH, 30);
				cal2.set(Calendar.MONTH, Calendar.DECEMBER);
				if (term !=null) {
					cal2.set(Calendar.YEAR, Integer.valueOf(term));
				}
				Date endDate = cal2.getTime();
				LOG.debug("got cal:" + cal2.get(Calendar.YEAR) + "/" + cal2.get(Calendar.MONTH) + "/" + cal2.get(Calendar.DAY_OF_MONTH));
				courseAdmin.createCourseOffering(courseEid, courseEid, "someDescription", "active", term, courseCode, startDate, endDate);
				courseAdmin.addCourseOfferingToCourseSet(setId, courseEid);
			}


			if (! cmService.isEnrollmentSetDefined(courseEid))
				courseAdmin.createEnrollmentSet(courseEid, "title", "description", "category", "defaultEnrollmentCredits", courseEid, null);

			if(! cmService.isSectionDefined(courseEid))
				courseAdmin.createSection(courseEid, courseEid, "description", "course", null, courseEid, null);




			courseAdmin.addOrUpdateSectionMembership(userId, role, courseEid, "enroled");
			courseAdmin.addOrUpdateEnrollment(userId, courseEid, "enrolled", "NA", "0");
			//now add the user to a section of the same name
			try {
				cmService.getSection(courseEid);
			} 
			catch (IdNotFoundException id) {
				//create the CO
				//lets create the 2007 academic year :-)
				//create enrolmentset
				courseAdmin.createEnrollmentSet(courseEid, courseEid, "description", "category", "defaultEnrollmentCredits", courseEid, null);
				LOG.info("creating Section for " + courseCode + " in year " + term);
				getCanonicalCourse(courseCode);
				courseAdmin.createSection(courseEid, courseEid, "someDescription","course",null,courseEid,courseEid);
			}
			courseAdmin.addOrUpdateSectionMembership(userId, role, courseEid, "enroled");
		}
		catch(Exception e) {
			e.printStackTrace();

		}


	}

	//remove user from old courses
	private void synchCourses(List<String> uctCourse, String userEid){
		LOG.debug("Checking enrolments for " + userEid);
		SimpleDateFormat yearf = new SimpleDateFormat("yyyy");
		String thisYear = yearf.format(new Date());

		courseAdmin = getCourseAdmin();
		cmService =getCourseManagementService();

		Set<EnrollmentSet> enroled = cmService.findCurrentlyEnrolledEnrollmentSets(userEid);
		Iterator<EnrollmentSet> coursesIt = enroled.iterator();
		LOG.debug("got list of enrolement set with " + enroled.size());
		while(coursesIt.hasNext()) {
			EnrollmentSet eSet = (EnrollmentSet)coursesIt.next();
			String courseEid =  eSet.getEid();
			LOG.debug("got section: " + courseEid);
			boolean found = false;
			for (int i =0; i < uctCourse.size(); i++ ) {
				String thisEn = (String)uctCourse.get(i) + "," + thisYear;
				if (thisEn.equalsIgnoreCase(courseEid))
					found = true;
			}
			if (!found) {
				LOG.info("removing user from " + courseEid);
				courseAdmin.removeCourseOfferingMembership(userEid, courseEid);
				courseAdmin.removeSectionMembership(userEid, courseEid);
				courseAdmin.removeEnrollment(userEid, courseEid);


			}
		}

	}


	private void getCanonicalCourse(String courseCode) {
		try {
			cmService.getCanonicalCourse(courseCode);
		}
		catch (IdNotFoundException id) {
			LOG.info("creating canonicalcourse " + courseCode);
			courseAdmin.createCanonicalCourse(courseCode, "something", "something else");
		}
	}



	private String fixPhoneNumber(String number) {
		number=number.replaceAll("/","");
		number = number.replaceAll("-","");
		number = number.replaceAll(" ","");
		return number;

	}
	
	@SuppressWarnings("unchecked")
	private String getOrgCodeById(String modOrgUnit) {
		String statement = "Select org from UCT_ORG where ORG_UNIT = " + modOrgUnit;

		List<String> result = m_sqlService.dbRead(statement);
		if (result.size()>0) {
			LOG.info("got org unit of " + (String)result.get(0));
			return (String)result.get(0);
		} else {
			LOG.warn("Unknown org code of " + modOrgUnit + " recieved" );
		}

		return null;
	}

	private boolean residenceIsCurrent(String resCode) {

		if (resCode == null)
			return false;

		LOG.debug("checking if this resCode is current: " + resCode);

		/* rescode is of the form RES*YYYY-MM-DD*YYYY-MM-DD
		 * we need to parse these to dates
		 */
		try {
			//String startDate = resCode.substring(4, 14);
			String endDate =resCode.substring(15);
			DateFormat df = new SimpleDateFormat("yyyy-MM-dd");
			Date end = df.parse(endDate);

			/* we should always add in this case - may not get their details again
			if (start.after(new Date()))
				return false;
			 */
			if (end.before(new Date())) {
				LOG.debug("residence end date is in the past");
				return false;

			}

		} catch (ParseException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (Exception ex) {
			ex.printStackTrace();
		}



		return true;

	}

} //end class
