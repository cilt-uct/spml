<?php
/*
Send an SPML SOAP packet to the SPML webservice

*/


#$url="https://sakai.cet.uct.ac.za/sakai-axis/SPML2.jws?wsdl";
#$url="https://sakai.cet.uct.ac.za/sakai-axis/SakaiScript.jws?wsdl";
#$url="https://srvslscle005.uct.ac.za:8443/sakai-spml/spmlrouter";
$url="http://localhost:8080/sakai-spml/spmlrouter";

$SOAP_packet="<soap-env:Envelope xmlns:soap-env=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap-env:Body>
<spml:addRequest xmlns:spml='urn:oasis:names:tc:SPML:1:0' xmlns:dsml='urn:oasis:names:tc:DSML:2:0:core'>
  <spml:attributes>
    <attr name='objectclass'>
      <value>User</value>
    </attr>
    <attr name='CN'>
      <value>HLLLEE004</value>
    </attr>
    <attr name='Surname'>
      <value>Hill</value>
    </attr>
    <attr name='Given Name'>
      <value>Lee</value>
    </attr>
    <attr name='Initials'>
      <value>LD</value>
    </attr>
    <attr name='GUID'>
      <value>0L3PU0WU3AGApQAWNTpmgw==</value>
    </attr>
    <attr name='Email'>
      <value>HLLLEE004@uct.ac.za</value>
    </attr>
    <attr name='mobile'>
      <value>083/266-8650</value>
    </attr>
    <attr name='homePhone'>
      <value>021/554-2726</value>
    </attr>
    <attr name='eduPersonPrimaryAffiliation'>
      <value>Student</value>
    </attr>
    <attr name='uctCampusID'>
      <value>HLLLEE004</value>
    </attr>
    <attr name='uctFaculty'>
      <value>SCI</value>
    </attr>
    <attr name='uctPersonalTitle'>
      <value>Mr</value>
    </attr>
    <attr name='uctProgramCode'>
      <value>SB013</value>
    </attr>
    <attr name='uctStudentNumber'>
      <value>1320956</value>
    </attr>
    <attr name='uctStudentStatus'>
      <value>Active</value>
    </attr>
    <attr name='DOB'>
      <value>19890118</value>
    </attr>
    <attr name='uctCourseCode'>
      <value>PSY1001W,BIO1000F,BIO1004S,CEM1000W,STA1007S,MAM1004H</value>
    </attr>
    <attr name='uctProgramCode'>
      <value>SB013</value>
    </attr>
    <attr name='Email'>
      <value>HLLLEE004@uct.ac.za</value>
    </attr>
    <attr name='Event_Type'>
      <value>Input</value>
    </attr>
  </spml:attributes>
</spml:addRequest>

</soap-env:Body></soap-env:Envelope>";



	#global $debug;

$user_agent = "Mozilla/4.0 (compatible; MSIE 5.01; Windows NT 5.0)";
$debug=true;
if ($debug) {
 echo "posting file... to $url \n";

}
// init curl handle

$ch = curl_init($url) or die("couldn't init curl");
curl_setopt($ch, CURLOPT_URL, $url);
curl_setopt($ch, CURLOPT_VERBOSE, 1);
curl_setopt($ch, CURLOPT_HEADER, 0);
curl_setopt($ch, CURLOPT_HTTPHEADER,array("Content-Type: text/xml","SOAPAction: \"\""));
curl_setopt($ch, CURLOPT_FOLLOWLOCATION,1);
curl_setopt($ch, CURLOPT_RETURNTRANSFER, 1);
curl_setopt($ch, CURLOPT_POST, 1);
curl_setopt($ch, CURLOPT_POSTFIELDS, $SOAP_packet);
curl_setopt($ch, CURLOPT_HTTPPROXYTUNNEL, false);
#curl_setopt($ch, CURLOPT_PROXY,"cache1.uct.ac.za:8080");
#curl_setopt($ch, CURLOPT_PROXYPORT,8080);
curl_setopt($ch, CURLOPT_SSL_VERIFYHOST,  2);
curl_setopt($ch, CURLOPT_USERAGENT, $user_agent);
curl_setopt($ch, CURLOPT_RETURNTRANSFER,1);
curl_setopt($ch, CURLOPT_SSL_VERIFYPEER, FALSE);  // this line makes it work under https
curl_setopt($ch, CURLOPT_SSL_VERIFYPEER,0);

	  // perform post
	  $rr=curl_exec($ch);

	      #echo $rr;
	      if ($rr) {
		print "***RETURNED: \n";
		print $rr;
	      } else {
#echo "nothing back!";
		echo "<br>error: ".curl_errno($ch)."---".curl_error($ch)."<br>";
		return $rr;
	      }
	      curl_close($ch);


?>

  
