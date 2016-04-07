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

my $spml;
my $status;

# Program code

my $spmlfile = $ARGV[0];

die "Please specify spml input file\n" if !defined($spmlfile);

#### TEST 1

print "\nSPML post of $spmlfile\n";

$spml = read_file($spmlfile);
$status = post_spml($host, $spml);

print "Status: $status\n";

print "Finished.\n";

