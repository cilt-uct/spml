<?
$numadd=15000;
#sakai-spml/servlet/spmlrouter
#$url="http://localhost:8080/axis/servlet/spmlrouter";
$url="http://localhost:8080/sakai-spml/spmlrouter";
$fh=fopen("/tmp/batchspml","w");

$pre="<soap-env:Envelope xmlns:soap-env=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap-env:Body><batchRequest xmlns:spml=\"urn:oasis:names:tc:SPML:1:0\" requestID=\"A4DF567HGD\"
processing =\"parallel\" execution=\"synchronous\">";
$SOAP_packet=$pre;


fwrite($fh,$pre);


$post="</batchRequest></soap-env:Body></soap-env:Envelope>";




for ($i=0;$i<=$numadd;$i++) {
$addreq = "<addRequest>
<attributes>
<attr name=\"objectclass\"> <value>User</value></attr>
<attr name=\"CN\"> <value>User".$i."</value></attr>
<attr name=\"Given Name\"><value>Something".$i."</value></attr>
<attr name=\"Surname\"><value>Surname".$i."</value></attr>
</attributes>
</addRequest>\n";
fwrite($fh,$addreq);
print "adding request $i \n";
$SOAP_packet.=$addreq; 
}
fwrite($fh,$post);
$SOAP_packet.=$post;

fclose($fh);
//OK we know have the file lets send it ..$user_agent = "Mozilla/4.0 (compatible; MSIE 5.01; Windows NT 5.0)";
$debug=true;
if ($debug) {
 echo "posting file... to $url \n";

}

$stime=mktime();
print "starting at $stime \n";
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
//curl_setopt($ch,CURLOPT_INFILE,"/tmp/batchspml");
print "about to post: \n";
//print $SOAP_packet;
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


$etime=mktime();
print "finishing at $etime \n";
$taken = $etime - $stime;
print "operation took $taken seconds \n";
?>