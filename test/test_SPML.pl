#! /usr/bin/perl

use HTTP::Request::Common qw(PUT POST DELETE);
use LWP::UserAgent;
use Data::Dumper;
use File::Slurp;
use strict;

require 'spmltestlib.pl';

my $debug = 0;
my $host = "https://devslscle001.uct.ac.za";
$ENV{'PERL_LWP_SSL_VERIFY_HOSTNAME'} = 0;

# Username (eid) used in the spml test templates
my $eid = "ABCXYZ989";

# Current year
my $year = "2014";

my $spml;
my $status;
my %courses;

# Program code

print "\nTest 1: Verifying set program code for current year\n";

$spml = read_file('spml-1.xml');
$spml =~ s/%TEST_PROGRAM_CODE%/XY987/;
$status = post_spml($host, $spml);
%courses = getCmCourses($eid);

if (!defined($courses{"XY987,$year"})) {	
	die "\n*** Test 1 failed: program code not set correctly\n";
}

print "\nTest 2: Verifying update program code for current year\n";

$spml = read_file('spml-1.xml');
$spml =~ s/%TEST_PROGRAM_CODE%/XY123/;
$status = post_spml($host, $spml);
%courses = getCmCourses($eid);

# New program code has been set
if (!defined($courses{"XY123,$year"})) {	
	die "\n*** Test 2 failed: updated program code not set correctly\n";
}

# Old program code has been cleared
if (defined($courses{"XY987,$year"})) {	
	die "\n*** Test 2 failed: old program code not cleared correctly\n";
}


print "All tests passed.\n";

sub post_spml
{
 my $host = shift;
 my $spmlbody = shift;

  my $spml = "<soap-env:Envelope xmlns:soap-env=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap-env:Body>" . $spmlbody .
             "</soap-env:Body></soap-env:Envelope>";

  my $url = $host . "/sakai-spml/spmlrouter";

  my $ua= LWP::UserAgent->new;
  $ua->timeout(10);
  $ua->env_proxy;

  my $req = HTTP::Request->new(
      POST => $url);
  $req->content_type('text/xml');
  $req->header('SOAPAction' => '""');
  $req->content($spml);

  my $res = $ua->request($req);

  if ($res->is_error()) {
 	print "request to $url failed\n";
  }

  return ($res->is_success);
}

