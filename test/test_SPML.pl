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

#### TEST 1

print "\nTest 1: Verifying set program code for current year\n";

$spml = read_file('spml-1.xml');
$spml =~ s/%TEST_PROGRAM_CODE%/XY987/;
$status = post_spml($host, $spml);
%courses = getCmCourses($eid);

if (!defined($courses{"XY987,$year"})) {	
	die "\n*** Test 1 failed: program code not set correctly\n";
}

#### TEST 2

print "\nTest 2: Verifying update program code for current year - VULA-1980\n";

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

#### TEST 3 - Drop program/faculty code if not registered for at least 1 course

print "\nTest 3: Verifying program and Faculty code unset if not registered for any courses - VULA-2067\n";

$spml = read_file('spml-2.xml');
$spml =~ s/%TEST_PROGRAM_CODE%/XY123/;
$status = post_spml($host, $spml);
%courses = getCmCourses($eid);

if (defined($courses{"XY123,$year"})) {	
	die "\n*** Test 3 failed: Program code set when not registered for any courses\n";
}

if (defined($courses{"SCI_STUD,$year"})) {	
	die "\n*** Test 3 failed: Faculty code set when not registered for any courses\n";
}

#### TEST 4 - Mobile number normalization

print "\nTest 4: Mobile number normalization - VULA-2131\n";

$spml = read_file('spml-4.xml');
$spml =~ s#%MOBILE%#083/123-4567#;
$status = post_spml($host, $spml);

my $mobile = getNormalizedMobile($eid);
die "\n*** Test 4 failed: Mobile number 083/123-4567 not normalized correctly\n" if ($mobile ne "27831234567");

$spml = read_file('spml-4.xml');
$spml =~ s#%MOBILE%#083-123-4567#;
$status = post_spml($host, $spml);

my $mobile = getNormalizedMobile($eid);
die "\n*** Test 4 failed: Mobile number 083-123-4567 not normalized correctly\n" if ($mobile ne "27831234567");

$spml = read_file('spml-4.xml');
$spml =~ s#%MOBILE%#0270831234567#;
$status = post_spml($host, $spml);

my $mobile = getNormalizedMobile($eid);
die "\n*** Test 4 failed: Mobile number 0270831234567 not normalized correctly\n" if ($mobile ne "27831234567");


#### FINISHED

print "\nAll tests passed.\n";

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

