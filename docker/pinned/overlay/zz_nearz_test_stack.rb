# Mounted into the PINNED TEST STACK only (nearz-api-tests docker/pinned), as
# config/initializers/zz_nearz_test_stack.rb. It is not part of the backend
# repository and changes no behaviour the API tests assert.
#
# It stops every message that would leave the machine, so a test booking never
# reaches a real phone or the team Slack:
#   - Slack: both SlackNotifier clients become silent loggers
#   - push:  InAppNotificationService and FcmWebNotificationService log instead
#            of calling Google; FcmApi still writes the in-app Notification row,
#            which is what the suite reads
#   - SMS:   TwoFactor (2factor.in OTP) answers locally; nothing is sent
#   - mail:  ActionMailer's :test delivery (kept in memory). Development uses
#            letter_opener, which tries to open a browser the container does
#            not have, so every mail job failed and retried
#
# Active only when NEARZ_TEST_STACK=true in development.

if Rails.env.development? && ENV['NEARZ_TEST_STACK'] == 'true'
  module NearzTestStack
    class SilentSlack
      def ping(message, *_args)
        Rails.logger.info("[test-stack] Slack suppressed: #{message.to_s[0, 160]}")
        nil
      end
    end

    OTP_SENT = { 'Status' => 'Success', 'Details' => 'test-stack: no SMS sent' }.freeze
    OTP_REJECTED = { 'Status' => 'Error', 'Details' => 'test-stack: OTP Mismatch' }.freeze
  end

  ActiveSupport.on_load(:action_mailer) { self.delivery_method = :test }

  if defined?(SlackNotifier)
    %i[CLIENT EXCEPTION_CLIENT].each do |name|
      SlackNotifier.send(:remove_const, name) if SlackNotifier.const_defined?(name, false)
      SlackNotifier.const_set(name, NearzTestStack::SilentSlack.new)
    end
  end

  Rails.application.config.to_prepare do
    [InAppNotificationService, FcmWebNotificationService].each do |service|
      service.class_eval do
        def initialize(*); end

        def send_notification(_token, title, *_rest)
          Rails.logger.info("[test-stack] push suppressed: #{title}")
          nil
        end
      end
    end

    TwoFactor.singleton_class.class_eval do
      %i[send_passcode send_passcode_with_template send_passcode_with_custom_otp].each do |name|
        define_method(name) { |*_args| NearzTestStack::OTP_SENT }
      end
      define_method(:verify_passcode) { |*_args| NearzTestStack::OTP_REJECTED }
    end
  end
end
