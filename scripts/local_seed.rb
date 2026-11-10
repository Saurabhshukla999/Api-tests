# Seeds the LOCAL Docker backend for the API suite. Idempotent: safe to run
# again; it only creates what is missing. Run through scripts/local-backend.sh,
# which copies this file into the web container and runs it with rails runner.
#
# Two salons, two different owners:
#   journey salon - every write in the suite (salon 1 if it exists)
#   other salon   - a separate tenant with staff, for the cross-salon checks
# Owner phones use the reserved 600 test block (see testPhonePrefix).
#
# Prints KEY=VALUE lines; the shell script turns them into
# src/test/resources/config.local.properties. Tokens are never logged.

abort 'local_seed.rb refuses to run outside development' unless Rails.env.development?

def owner(mobile, name)
  user = User.find_or_initialize_by(mobile: mobile)
  user.username ||= name
  user.role = :salon_owner
  user.save!
  user
end

def gst!(salon)
  SalonTax.find_or_create_by!(salon_id: salon.id) do |tax|
    tax.gst_number = '27AAAAA0000A1Z5'
    tax.cgst = 9
    tax.sgst = 9
    tax.igst = 0
    tax.gst_registered = true
  end
end

journey = Salon.find_by(id: (ENV['JOURNEY_SALON_ID'] || 1).to_i)
unless journey
  journey_owner = owner('+916000000001', 'QA Local Journey Owner')
  journey = Salon.find_or_create_by!(user_id: journey_owner.id) do |s|
    s.name = 'QA Local Journey Salon'
    s.active = true
  end
end
gst!(journey)

other_owner = owner('+916000000002', 'QA Local Other Owner')
other = Salon.find_or_create_by!(user_id: other_owner.id) do |s|
  s.name = 'QA Local Other Salon'
  s.active = true
end
gst!(other)

puts "JOURNEY_SALON=#{journey.id}"
puts "OTHER_SALON=#{other.id}"
puts "OTHER_STAFF=#{Staff.where(salon_id: other.id).count}"
puts "JOURNEY_TOKEN=#{JsonWebToken.encode(user_id: journey.user_id)}"
puts "OTHER_TOKEN=#{JsonWebToken.encode(user_id: other_owner.id)}"
