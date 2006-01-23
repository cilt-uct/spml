<?
/*
Send an SPML SOAP packet to the SPML webservice

*/


#$url="https://sakai.cet.uct.ac.za/sakai-axis/SPML2.jws?wsdl";
#$url="https://sakai.cet.uct.ac.za/sakai-axis/SakaiScript.jws?wsdl";
#$url="http://localhost:8080/axis/servlet/spmlrouter";
$url="http://localhost:8080/sakai-spml/spmlrouter";

$SOAP_packet="<soap-env:Envelope xmlns:soap-env=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap-env:Body><addRequest xmlns=\"urn:oasis:names:tc:SPML:1:0\"><attributes><attr name=\"objectclass\"><value>User</value></attr><attr name=\"CN\"><value>BSTSEA001TTXTIA001</value></attr><attr name=\"Surname\"><value>Tait
       </value></attr><attr name=\"Full Name\"><value>Tiana Tait</value></attr><attr name=\"Given Name\"><value>Tiana</value></attr><attr name=\"Initials\"><value>T</value></attr><attr name=\"nspmDistributionPassword\"><value>BSTSEA001</value></attr></attributes></addRequest></soap-env:Body></soap-env:Envelope>";



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

  
