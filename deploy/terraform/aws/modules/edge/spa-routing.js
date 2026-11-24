// CloudFront viewer-request function on the console (S3) behavior only; /api/*, /idp/* and
// /actuator/health* are separate behaviors routed to the ALB and never reach this code.
// Client-side routes such as /orders/123 have no file extension: serve the SPA shell for them.
function handler(event) {
  var request = event.request;
  var lastSegment = request.uri.substring(request.uri.lastIndexOf('/') + 1);
  if (lastSegment.indexOf('.') === -1) {
    request.uri = '/index.html';
  }
  return request;
}
