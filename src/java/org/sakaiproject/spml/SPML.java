/**********************************************************************************
* $URL: https://source.sakaiproject.org/contib/spml/src/java/org/sakaiporject/spml/SPML.java $
* $Id: SPML.java 3197 2005-12-22 00:30:28Z dhorwitz@ched.uct.ac.za $
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
 * @author Nuno Fernandez, Universidade Fernando Pessoa
 * @version $Revision: 3197 $
 */
package org.sakaiproject.SPML;

import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import java.util.Collection;
import java.util.Stack;
import java.net.URLEncoder;
import org.apache.axis.AxisFault;
import javax.servlet.http.HttpServletRequest;

import org.openspml.client.*;
import org.openspml.util.*;
import org.openspml.message.*;
import org.openspml.server.SpmlHandler;

import org.sakaiproject.api.kernel.session.Session;
import org.sakaiproject.api.kernel.session.cover.SessionManager;

import org.sakaiproject.service.legacy.user.cover.UserDirectoryService;
import org.sakaiproject.service.legacy.user.UserEdit;
import org.sakaiproject.service.legacy.user.User;
import org.sakaiproject.service.legacy.security.cover.SecurityService;
import org.sakaiproject.util.java.StringUtil;

import org.sakaiproject.api.common.manager.Persistable;


import org.sakaiproject.service.framework.sql.SqlReader;
import org.sakaiproject.service.framework.sql.SqlService;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
// Profile stuff
import org.sakaiproject.api.app.profile.*;
import org.sakaiproject.component.app.profile.*;
import org.sakaiproject.component.common.edu.person.SakaiPersonImpl;
import org.sakaiproject.api.common.edu.person.SakaiPerson;
import org.sakaiproject.api.common.edu.person.SakaiPersonManager;
import org.sakaiproject.api.common.agent.AgentGroupManager;
import org.sakaiproject.api.common.agent.Agent;
import org.sakaiproject.api.kernel.component.cover.ComponentManager;
import org.sakaiproject.api.common.superstructure.DefaultContainer;
import org.sakaiproject.api.common.type.Type;
import org.sakaiproject.api.common.type.UuidTypeResolvable;



//import javax.xml.parsers;


public class SPML implements SpmlHandler {
	
	//Atribute mappings to map SPML attributes to Sakai attributs
	
	
	//common name will be used for the Sakai userid
	private String cn = "CN";
	private String surname = "Surname";
	private String firstNames = "Given Name";
	private String email = "Email";
	private String userType = "eduPersonPrimaryAffiliation";
	private String courseMembership = "uctCourseCode";
	private String school ="uctFaculty";
	private String mobilePhone = "cellPhone";
	private String programCode = "uctProgramCode";
	private String homePhone ="homePhone";
	
	
	//change this to the name of your campus
	private String spmlCampus = "University of Cape Town";
	private String spmlUser = "spmluser";
	private String spmlPassword = "spmlpass";
	private String courseYear = "2006";
	
	
	
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

    //the Sakai Session ID
    private String sID;
    
    List attrList;
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
    
    
    
    private int profilesUpdated = 0;
    
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
    private AgentGroupManager agentGroupManager;
    private SqlService sqlService;
    
    public SakaiPersonManager getSakaiPersonManager() {
        if(sakaiPersonManager == null){
            sakaiPersonManager = (SakaiPersonManager) ComponentManager.get(SakaiPersonManager.class.getName());
        }
        return sakaiPersonManager;
    }
    public AgentGroupManager getAgentGroupManager() {
    	
        if(agentGroupManager == null){
        	System.out.println("Getting agentgroupmanager");
            agentGroupManager = (AgentGroupManager) ComponentManager.get(AgentGroupManager.class.getName());
            System.out.println("Got agentgroupmanager " + agentGroupManager);
        }
        return agentGroupManager;
    } 
    public SqlService getSqlService() {
    	
    if (sqlService == null){
    	sqlService = (SqlService)ComponentManager.get(SqlService.class.getName());
    }
    return sqlService;	
    
    }

    //////////////////////////////////////////////////////////////////////
    //
    // Constructors
    //
    //////////////////////////////////////////////////////////////////////


    public SpmlResponse doRequest(SpmlRequest req) {
	
	LOG.info("SPMLRouter recieved req " + req + " (id) ");
	profilesUpdated = 0;
	SpmlResponse resp = req.createResponse();
	//this.logSPMLRequest("Unknown",req.toXml());
	try {

	    //we need to login
	    //this will need to be changed - login can be sent via attributes to the object?
	    System.out.println("About to login");
	    sID = login(spmlUser,spmlPassword);
	    
	    if (req instanceof AddRequest) {
		AddRequest uctRequest = (AddRequest)req;
		resp = spmlAddRequest(uctRequest);
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
		LOG.info("Method not implemented");
	    }
	    

	    
	}
	catch (Exception e) {

	    e.printStackTrace();
	}
	LOG.info (this + " " + profilesUpdated +" profiles updated");
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

   	public SpmlResponse spmlAddRequest(AddRequest req) {
		LOG.info("SPML Webservice: Receiveid AddRequest "+req);
		//LOG.info(req.toXml());
		this.logSPMLRequest("Addrequest",req.toXml());
	
		List attrList = req.getAttributes();
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
		CN =(String)req.getAttributeValue(cn);
		CN = CN.toLowerCase();
		GN = (String)req.getAttributeValue(firstNames);
		String LN = (String)req.getAttributeValue(surname);
		LN = LN.trim();
		String thisEmail = (String)req.getAttributeValue(email);
		//always lower case
		String type = (String)req.getAttributeValue(userType);
		//System.out.
		type = type.toLowerCase();
		String passwd = "";
		
		String mobile = (String)req.getAttributeValue(mobilePhone);
		if (mobile!= null ) {
			mobile ="";
		}
		SpmlResponse response = null;
		try {


		    String addeduser = null;
		    System.out.println("About to add " + CN + " of type " + type + " from " +(String)req.getAttributeValue("uctFaculty"));
		    addeduser = addNewUser(sID,CN,GN,LN,thisEmail,type,passwd);
		    
		    //try populate the profile
		    
		    
		    if (addeduser.equals("success")) {
		    	
		    	String profileAdd = addnewUserProfile(sID,CN,GN,LN,thisEmail,type,passwd, mobile);
		    	if (profileAdd.equals("success")) {
		    		response = req.createResponse();
		    	} else {
			    	response = req.createResponse();
			    	//response.setError(profileAdd);
			    	//response.setResult("Profile Failure - account added");		    		
			    	System.out.println("WARN: "+ this + " profile error" + profileAdd);
			    	
			    	
			    	
		    	}
		    	
		    } else {
		    	response = req.createResponse();
		    	//for now were not sending these back to eds
		    	System.out.println("WARN: "+ this + " adduser error" + addeduser);
		    	String thisProfileAdd = addnewUserProfile(sID,CN,GN,LN,thisEmail,type,passwd, mobile);

		    }
		}
		catch(Exception e) {
		    e.printStackTrace();
		}
		
		//lets try the course membership 
		// its a comma delimeted list in the uctCourseCode attribute
		//however it might be null - if so ignore and move on
		removeUserFromAllCourses(CN);
		try {
			String uctCourses =null;
			uctCourses = (String)req.getAttributeValue(programCode);
			if ((String)req.getAttributeValue(courseMembership)!=null) {
				uctCourses = uctCourses + "," +(String)req.getAttributeValue(courseMembership);
			}
			uctCourses = uctCourses + "," + (String)req.getAttributeValue("uctFaculty") + "_"+ (String)req.getAttributeValue("eduPersonPrimaryAffiliation");
			if (uctCourses!=null) {
				if (uctCourses.length()>0) {
					String[] uctCourse =  StringUtil.split(uctCourses, ",");
					//System.out.println("got " + uctCourse.length + " courses");
					for (int ai = 0; ai < uctCourse.length; ai ++ ) {
						//System.out.println("got a coursecode " + uctCourse[ai]);
						String x = addUserToCourse(CN,uctCourse[ai]);
					}
				}
			}
		}
		catch (Exception e) {
			//Nothing to do...
			//error adding users to course
			//e.printStackTrace();
			
		}
		return response;
	} 
	
	public SpmlResponse spmlDeleteRequest(SpmlRequest req) {
	    LOG.info("SPML Webservice: Recieveid DeleteRequest "+req);
	    this.logSPMLRequest("DeleteRequest",req.toXml());
	    SpmlResponse response = null;
	    return response;
	} 

	public SpmlResponse spmlModifyRequest(ModifyRequest req) {
		LOG.info("SPML Webservice: Recieveid DeleteRequest "+req);

		this.logSPMLRequest("ModifyRequest",req.toXml());
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
	    System.out.println("got mods for " + CN);
	    //we now need to find what has changed 
	    //first we need the exisiting values
	    UserDirectoryService userDir = new UserDirectoryService();
	    String GN= "";
	    String LN = "";
	    String thisEmail = "";
	    String type = "";

	    try {
		User thisUser = userDir.getUser(CN);
	    
		GN = thisUser.getFirstName();
		LN = thisUser.getLastName();
		thisEmail = thisUser.getEmail();
		type = thisUser.getType();
	    }
	    catch (Exception e) {
		System.out.println(e);
	    }
	    try {
		List mods = req.getModifications();
		LOG.info("got " + mods.size() + " modifications");
		for (int i = 0; i <mods.size(); i++) {

		    LOG.info(mods.get(i));
		    Modification mod = (Modification)mods.get(i);
		    LOG.info(mod.getName());
		    //map the SPML names to their atributes
		    if (mod.getName().equals(firstNames)) {
			GN = (String)mod.getValue();
		    } else if (mod.getName().equals(surname)) {
			LN = (String)mod.getValue();
		    } else if (mod.getName().equals(email)) {
			thisEmail = (String)mod.getValue();
		    }

		      

		}
		}
		catch (Exception e) {
		    e.printStackTrace();
		}
	    
	    String passwd = "";
	    SpmlResponse response = null;

	    try {

		
		    //we need to login
		    //this will need to be changed - login can be sent via attributes to the object?
		String sID = login("admin","admin");
		String addResp = changeUserInfo(sID, CN, GN, LN, thisEmail, type, passwd);
		if (addResp.equals("success")) {
		    response = req.createResponse();
		} else {
		    response = req.createResponse();
		    response.setError(addResp);
		    response.setResult("failure");
		}
	    }
	    catch(Exception e) {
		e.printStackTrace();
	    }
		
	    return response;
	} 		

	public SpmlResponse spmlBatchRequest(BatchRequest req) {
	    LOG.info("recieved SPML Batch Request");
	    SpmlResponse resp = null;
		
	    try {
	    	//we need to iterate throug through the units in the batch
		
		    	//get a list of the actual methods
			List requestList = req.getRequests(); 
			for (int i =0 ; i < requestList.size();i++) {
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

/*
 * Taken from SakaiScript
*/
private String addNewUser( String sessionid, String userid, String firstname, String lastname, String thisEmail, String type, String password) throws Exception
{
	Session session = establishSession(sessionid);
	//type is always lower case
	
	if (!SecurityService.isSuperUser())
	{
		LOG.warn("WARN: NonSuperUser trying to add accounts: " + session.getUserId());
        throw new Exception("NonSuperUser trying to add accounts: " + session.getUserId());
	}
	try {

		User addeduser = null;
		addeduser = UserDirectoryService.addUser(userid, firstname, lastname, thisEmail, password, type, null);
	
 	}
	catch (Exception e) {  
	 return e.getClass().getName() + " : " + e.getMessage();
	}
	return "success";
}
/*
 *  populate a users profile
 */
private String addnewUserProfile(String sessionid, String userid, String firstname, String lastname, String thisEmail, String type, String password, String mobile) throws Exception 
{
	LOG.info(this + " creating profile for "+ userid + " ");
	String ret = "";
	try {
		//we need both a user editable and a system profile
		updateUserProfile(userid, firstname, lastname, thisEmail,"", null, "UserMutableType", mobile);
		updateUserProfile(userid, firstname, lastname, thisEmail,"", null, "SystemMutableType", mobile);
		return "success";		
	}
	catch (Exception e) {
		e.printStackTrace();
	}
	return ret;
}
private Session establishSession(String id) throws Exception
{
	Session s = SessionManager.getSession(id);
	
	if (s == null)
	{
		throw new Exception("Session "+id+" is not active");
	}
	s.setActive();
	SessionManager.setCurrentSession(s);
	return s;
}

public String checkSession(String id) {
	Session s = SessionManager.getSession(id);
	if (s == null)
	{
		return "null";
	}
	else
	{
		return id;
	}
}

//well need to handle login ourselves
public String login(String id,String pw) {
	User user = UserDirectoryService.authenticate(id,pw);
	if ( user != null ) {
		sakaiSession = SessionManager.startSession();
		if (sakaiSession == null)
		{
			return "sessionnull";
		}
		else
		{
			sakaiSession.setUserId(id);
			sakaiSession.setUserEid(id);
			return sakaiSession.getId();
		}
	}
	return "usernull";
}

public String changeUserInfo( String sessionid, String userid, String firstname, String lastname, String thisEmail, String type, String password) throws AxisFault
{

    LOG.info("Editing info for " + userid + "," + firstname + "," + lastname + "," + thisEmail + "," + type +"," + password);
	try {
		//type is always lower case
		
	    UserEdit userEdit = null;
		userEdit = UserDirectoryService.editUser(userid);
		userEdit.setFirstName(firstname);
		userEdit.setLastName(lastname);
		userEdit.setEmail(thisEmail);
		userEdit.setType(type);
		userEdit.setPassword(password);
		UserDirectoryService.commitEdit(userEdit);
	
	}
	catch (Exception e) {  
	 return e.getClass().getName() + " : " + e.getMessage();
	}
	return "success";
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
	
    SakaiPersonManager spm = getSakaiPersonManager();
    //AgentGroupManager agentGroupManager = null;
    Agent agent = null;
    try{
    	agentGroupManager = getAgentGroupManager();
    	System.out.println("got agentgroupmanager" + agentGroupManager);
    	System.out.println("user is " + userId);
    	agent = agentGroupManager.getAgentBySessionManagerUserId(userId);
    }catch(Exception e1){
        LOG.error("Failed to get agentgroupManager " + userId + ": " + e1);
        e1.printStackTrace();
        return null;
    }
    
    Type _type = null;
    if (type.equals("UserMutableType")) {
    	setSakaiSessionUser(userId); // switch to that user's session
    	 _type = spm.getUserMutableType();
    } else {
    	 _type = spm.getSystemMutableType();
    }
    SakaiPerson sakaiPerson = null;
    if(agent != null){
        try{
            sakaiPerson = spm.getSakaiPerson(userId, _type);
            // create profile if it doesn't exist
            if(sakaiPerson == null){
                sakaiPerson = spm.create(userId, userId, _type);
                LOG.info(this + "creating profile for user " + userId + " of type " + _type);
            }
        }catch(Exception e){
            LOG.error("Unknown error occurred in getUserProfile(" + userId + "): " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    if (type.equals("UserMutableType")) {
    	//return to the admin user
    	setSakaiSessionUser(spmlUser);
    }
    
    return sakaiPerson;
}

/*
 * Set the session to the new user
 */
private synchronized void setSakaiSessionUser(String id) {
    
    
    sakaiSession.setUserId(id);
    sakaiSession.setUserEid(id);
    
} 

/*
 * update a profile
 */
private void updateUserProfile(String userId, String firstName, String lastName, String thisEmail, String dept, byte[] jpegPhoto, String type, String mobile) {
    if(userId == null || userId.equals("")){
        LOG.error("Failed to update profile for user: (null or empty).");
        return;
    }
    
    System.out.println("Updating profile for " + userId + "of type: " + type);
    userId = userId.toLowerCase();
    SakaiPerson sakaiPerson = getUserProfile(userId, type);
    System.out.println("got profile " + sakaiPerson);
    if(sakaiPerson != null){
        try{
            //sakaiPerson.setJpegPhoto(jpegPhoto);
            //sakaiPerson.setSystemPicturePreferred(Boolean.TRUE);
            sakaiPerson.setGivenName(firstName);
            sakaiPerson.setSurname(lastName);
            sakaiPerson.setDisplayName(firstName + " " + lastName);
            sakaiPerson.setMail(thisEmail);
            sakaiPerson.setCampus(spmlCampus);
            sakaiPerson.setDepartmentNumber(dept);
            sakaiPerson.setHidePrivateInfo(Boolean.TRUE);
            sakaiPerson.setMobile(mobile);
            SakaiPersonManager spm = getSakaiPersonManager();
            if (type.equals("UserMutableType")) {
            	setSakaiSessionUser(userId);
            }
            spm.save(sakaiPerson);
                       
            profilesUpdated++;
            if (type.equals("UserMutableType")) {
            	setSakaiSessionUser(spmlUser);  // get back the admin session
            }
        }catch(IllegalAccessError e){
            LOG.error("Failed to update profile for user " + userId + " - no permissions: " + e.getMessage());
        }catch(Exception e){
            LOG.error("Failed to update profile for user " + userId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
} 

	/*
	 * Log the request
	 */
	private void logSPMLRequest(String type, String body) {
		try {
			String escapeBody = body.replaceAll("'","''");
			String statement = "insert into spml_log (spml_type,spml_body, ipaddress) values ('" + type +"','" + escapeBody + "','" + requestIp + "')";
			getSqlService();
			//LOG.info(this + "SQLservice:" + sqlService);
			sqlService.dbWrite(statement);
		}
		catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	/*
	 * add the user to a course
	 * 
	 */
	private String addUserToCourse(String userId, String courseCode) {
		try {
			String statement= "insert into UCT_MEMBERSHIP (SOURCEDID_ID, MEMBER_SOURCEDID_ID,MEMBER_ROLE_ROLETYPE) values ('" + courseCode +"," + courseYear +"','" + userId  +"','Student')";
			getSqlService();
			sqlService.dbWrite(statement);
		}
		catch(Exception e) {
			e.printStackTrace();
			return "failure";
		}
		return "success";
	}
	
	private String removeUserFromAllCourses(String userId) {
		
		try {
			String statement= "delete from UCT_MEMBERSHIP  where lower(MEMBER_SOURCEDID_ID) = lower('" + userId +"') and SOURCEDID_ID like '%,2006'";
			getSqlService();
			sqlService.dbWrite(statement);
		}
		catch(Exception e) {
			e.printStackTrace();
			return "failure";
		}
		return "success";
		
	}
	
} //end class
