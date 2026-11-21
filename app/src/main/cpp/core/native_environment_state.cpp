// The process view and path mapping share the immutable Environment snapshot.
// Split them into focused implementation fragments while retaining one TU.
#include "parts/00_process_view.inl"
#include "parts/01_path_mapping.inl"
