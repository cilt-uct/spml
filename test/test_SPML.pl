#! /usr/bin/perl

use HTTP::Request::Common qw(PUT POST DELETE);
use LWP::UserAgent;
use Data::Dumper;
use File::Slurp;
use strict;

my $debug = 0;
my $host = "https://devslscle001.uct.ac.za";
$ENV{'PERL_LWP_SSL_VERIFY_HOSTNAME'} = 0;

my $spml1 = read_file('spml-1.xml');

my $status = post_spml($host, $spml1);

print "Post 1: $status\n";


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

